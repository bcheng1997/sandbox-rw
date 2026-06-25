`timescale 1ns/1ps

// -----------------------------------------------------------------------------
// Streaming FIR Filter Bank Stress-Test Design
//
// Balanced heterogeneous FPGA resource usage:
//   - LUT/FF: control, valid/ready, muxing, FSMs, pipeline registers
//   - CARRY: counters, checksum, accumulators, comparisons
//   - BRAM: coefficient RAMs, sample-history RAMs, output FIFO
//   - DSP: signed multiply-accumulate datapath
//
// Interface:
//   Input stream carries one sample at a time tagged with channel index.
//   Output stream emits filtered samples tagged with channel index.
//
// Notes:
//   This version uses one FIR engine shared across channels. That makes BRAM,
//   DSP, CARRY, and control usage explicit and scalable without requiring
//   enormous source code. Increase NUM_CHANNELS, TAPS, PARALLEL_MACS, and FIFO_DEPTH
//   to stress a placer harder.
// -----------------------------------------------------------------------------

module fir_filter_bank #(
    parameter integer NUM_CHANNELS  = 16,
    parameter integer TAPS          = 64,
    parameter integer PARALLEL_MACS = 4,
    parameter integer DATA_W        = 16,
    parameter integer COEFF_W       = 16,
    parameter integer ACC_W         = 40,
    parameter integer FIFO_DEPTH    = 512,
    parameter integer CH_W          = clog2(NUM_CHANNELS),
    parameter integer TAP_W         = clog2(TAPS)
)(
    input  wire                         clk,
    input  wire                         rst,

    // Input stream
    input  wire signed [DATA_W-1:0]      s_data,
    input  wire [CH_W-1:0]               s_chan,
    input  wire                          s_valid,
    output wire                          s_ready,
    input  wire                          s_last,

    // Coefficient write port
    input  wire                          coeff_we,
    input  wire [CH_W-1:0]               coeff_chan,
    input  wire [TAP_W-1:0]              coeff_tap,
    input  wire signed [COEFF_W-1:0]     coeff_data,

    // Output stream
    output wire signed [DATA_W-1:0]      m_data,
    output wire [CH_W-1:0]               m_chan,
    output wire                          m_valid,
    input  wire                          m_ready,
    output wire                          m_last,

    // Statistics
    output wire [31:0]                   stat_packet_count,
    output wire [31:0]                   stat_sample_count,
    output wire [31:0]                   stat_overflow_count,
    output wire [31:0]                   stat_checksum
);

    localparam integer PROD_W = DATA_W + COEFF_W;
    localparam integer MEM_DEPTH = NUM_CHANNELS * TAPS;
    localparam integer MEM_AW = clog2(MEM_DEPTH);
    localparam integer MAC_W = clog2(PARALLEL_MACS);

    // -------------------------------------------------------------------------
    // Sample history memory
    // Address layout:
    //   address = channel * TAPS + tap_index
    //
    // tap_index 0 is newest sample.
    // On each new sample, history shifts:
    //   x[TAPS-1] <= x[TAPS-2]
    //   ...
    //   x[1] <= x[0]
    //   x[0] <= new_sample
    //
    // This shift operation is intentionally BRAM/control heavy.
    // -------------------------------------------------------------------------

    reg signed [DATA_W-1:0] sample_mem [0:MEM_DEPTH-1];
    reg signed [COEFF_W-1:0] coeff_mem  [0:MEM_DEPTH-1];

    // Synthesis hints for common FPGA tools.
    // Vivado usually accepts these on arrays.
    (* ram_style = "block" *) reg signed [DATA_W-1:0] sample_mem_bram [0:MEM_DEPTH-1];
    (* ram_style = "block" *) reg signed [COEFF_W-1:0] coeff_mem_bram  [0:MEM_DEPTH-1];

    // Use the hinted arrays internally.
    // The unused plain arrays above are intentionally omitted from logic by synthesis.
    // They are left here only to make the intended memory model obvious.

    reg signed [DATA_W-1:0]  in_data_r;
    reg [CH_W-1:0]           in_chan_r;
    reg                      in_last_r;

    reg [TAP_W-1:0]          shift_idx;
    reg [TAP_W-1:0]          mac_base;
    reg [CH_W-1:0]           active_chan;
    reg                      active_last;

    reg signed [ACC_W-1:0]   acc;
    reg signed [ACC_W-1:0]   acc_next;
    reg signed [ACC_W-1:0]   rounded_sat;

    reg [2:0] state;
    localparam [2:0]
        ST_IDLE       = 3'd0,
        ST_SHIFT_READ = 3'd1,
        ST_SHIFT_WRITE= 3'd2,
        ST_MAC        = 3'd3,
        ST_OUTPUT     = 3'd4;

    reg signed [DATA_W-1:0] shift_temp;

    integer i;

    wire [MEM_AW-1:0] coeff_wr_addr =
        coeff_chan * TAPS + coeff_tap;

    always @(posedge clk) begin
        if (coeff_we) begin
            coeff_mem_bram[coeff_wr_addr] <= coeff_data;
        end
    end

    // -------------------------------------------------------------------------
    // Output FIFO
    // -------------------------------------------------------------------------

    wire fifo_full;
    wire fifo_empty;
    wire fifo_wr_en;
    wire fifo_rd_en;

    reg signed [DATA_W-1:0] fifo_din_data;
    reg [CH_W-1:0]          fifo_din_chan;
    reg                     fifo_din_last;

    wire signed [DATA_W-1:0] fifo_dout_data;
    wire [CH_W-1:0]          fifo_dout_chan;
    wire                     fifo_dout_last;

    assign fifo_wr_en = (state == ST_OUTPUT) && !fifo_full;
    assign fifo_rd_en = m_valid && m_ready;

    sync_fifo #(
        .DATA_W(DATA_W + CH_W + 1),
        .DEPTH(FIFO_DEPTH)
    ) out_fifo (
        .clk(clk),
        .rst(rst),
        .wr_en(fifo_wr_en),
        .din({fifo_din_last, fifo_din_chan, fifo_din_data}),
        .full(fifo_full),
        .rd_en(fifo_rd_en),
        .dout({fifo_dout_last, fifo_dout_chan, fifo_dout_data}),
        .empty(fifo_empty)
    );

    assign m_valid = !fifo_empty;
    assign m_data  = fifo_dout_data;
    assign m_chan  = fifo_dout_chan;
    assign m_last  = fifo_dout_last;

    // Backpressure: accept input only when idle and output FIFO has room.
    assign s_ready = (state == ST_IDLE) && !fifo_full;

    // -------------------------------------------------------------------------
    // MAC datapath
    // Multiple multipliers are instantiated in parallel to infer DSPs.
    // -------------------------------------------------------------------------

    reg signed [DATA_W-1:0]  mac_sample [0:PARALLEL_MACS-1];
    reg signed [COEFF_W-1:0] mac_coeff  [0:PARALLEL_MACS-1];
    reg signed [PROD_W-1:0]  mac_prod   [0:PARALLEL_MACS-1];

    reg signed [ACC_W-1:0] partial_sum;

    integer lane;
    reg [TAP_W:0] tap_ext;

    always @(*) begin
        partial_sum = {ACC_W{1'b0}};
        for (lane = 0; lane < PARALLEL_MACS; lane = lane + 1) begin
            mac_prod[lane] = mac_sample[lane] * mac_coeff[lane];
            partial_sum = partial_sum + {{(ACC_W-PROD_W){mac_prod[lane][PROD_W-1]}}, mac_prod[lane]};
        end
    end

    // Saturate ACC_W result back to DATA_W.
    wire signed [ACC_W-1:0] max_pos = {{(ACC_W-DATA_W){1'b0}}, 1'b0, {(DATA_W-1){1'b1}}};
    wire signed [ACC_W-1:0] min_neg = {{(ACC_W-DATA_W){1'b1}}, 1'b1, {(DATA_W-1){1'b0}}};

    function signed [DATA_W-1:0] sat_to_data;
        input signed [ACC_W-1:0] x;
        begin
            if (x > max_pos)
                sat_to_data = {1'b0, {(DATA_W-1){1'b1}}};
            else if (x < min_neg)
                sat_to_data = {1'b1, {(DATA_W-1){1'b0}}};
            else
                sat_to_data = x[DATA_W-1:0];
        end
    endfunction

    wire overflow_now = (acc > max_pos) || (acc < min_neg);

    // -------------------------------------------------------------------------
    // Statistics block
    // -------------------------------------------------------------------------

    reg [31:0] packet_count_r;
    reg [31:0] sample_count_r;
    reg [31:0] overflow_count_r;
    reg [31:0] checksum_r;

    assign stat_packet_count   = packet_count_r;
    assign stat_sample_count   = sample_count_r;
    assign stat_overflow_count = overflow_count_r;
    assign stat_checksum       = checksum_r;

    // -------------------------------------------------------------------------
    // Main FSM
    // -------------------------------------------------------------------------

    wire [MEM_AW-1:0] active_base_addr = active_chan * TAPS;
    wire [MEM_AW-1:0] in_base_addr     = in_chan_r * TAPS;

    always @(posedge clk) begin
        if (rst) begin
            state            <= ST_IDLE;
            in_data_r         <= {DATA_W{1'b0}};
            in_chan_r         <= {CH_W{1'b0}};
            in_last_r         <= 1'b0;
            active_chan       <= {CH_W{1'b0}};
            active_last       <= 1'b0;
            shift_idx         <= {TAP_W{1'b0}};
            mac_base          <= {TAP_W{1'b0}};
            acc               <= {ACC_W{1'b0}};
            shift_temp        <= {DATA_W{1'b0}};
            fifo_din_data     <= {DATA_W{1'b0}};
            fifo_din_chan     <= {CH_W{1'b0}};
            fifo_din_last     <= 1'b0;
            packet_count_r    <= 32'd0;
            sample_count_r    <= 32'd0;
            overflow_count_r  <= 32'd0;
            checksum_r        <= 32'd0;

            for (i = 0; i < PARALLEL_MACS; i = i + 1) begin
                mac_sample[i] <= {DATA_W{1'b0}};
                mac_coeff[i]  <= {COEFF_W{1'b0}};
            end
        end else begin
            case (state)
                ST_IDLE: begin
                    if (s_valid && s_ready) begin
                        in_data_r       <= s_data;
                        in_chan_r       <= s_chan;
                        in_last_r       <= s_last;
                        active_chan     <= s_chan;
                        active_last     <= s_last;
                        shift_idx       <= TAPS-1;
                        sample_count_r  <= sample_count_r + 1;
                        checksum_r      <= checksum_r + {{(32-DATA_W){s_data[DATA_W-1]}}, s_data};

                        if (s_last)
                            packet_count_r <= packet_count_r + 1;

                        state <= ST_SHIFT_READ;
                    end
                end

                ST_SHIFT_READ: begin
                    // Read x[idx-1] before writing x[idx].
                    if (shift_idx != 0) begin
                        shift_temp <= sample_mem_bram[in_base_addr + shift_idx - 1];
                    end
                    state <= ST_SHIFT_WRITE;
                end

                ST_SHIFT_WRITE: begin
                    if (shift_idx == 0) begin
                        sample_mem_bram[in_base_addr] <= in_data_r;
                        mac_base <= {TAP_W{1'b0}};
                        acc      <= {ACC_W{1'b0}};
                        state    <= ST_MAC;
                    end else begin
                        sample_mem_bram[in_base_addr + shift_idx] <= shift_temp;
                        shift_idx <= shift_idx - 1;
                        state <= ST_SHIFT_READ;
                    end
                end

                ST_MAC: begin
                    // Load parallel MAC lanes.
                    for (i = 0; i < PARALLEL_MACS; i = i + 1) begin
                        if ((mac_base + i) < TAPS) begin
                            mac_sample[i] <= sample_mem_bram[active_base_addr + mac_base + i];
                            mac_coeff[i]  <= coeff_mem_bram [active_base_addr + mac_base + i];
                        end else begin
                            mac_sample[i] <= {DATA_W{1'b0}};
                            mac_coeff[i]  <= {COEFF_W{1'b0}};
                        end
                    end

                    acc <= acc + partial_sum;

                    if ((mac_base + PARALLEL_MACS) >= TAPS) begin
                        state <= ST_OUTPUT;
                    end else begin
                        mac_base <= mac_base + PARALLEL_MACS;
                    end
                end

                ST_OUTPUT: begin
                    if (!fifo_full) begin
                        fifo_din_data <= sat_to_data(acc);
                        fifo_din_chan <= active_chan;
                        fifo_din_last <= active_last;

                        if (overflow_now)
                            overflow_count_r <= overflow_count_r + 1;

                        state <= ST_IDLE;
                    end
                end

                default: begin
                    state <= ST_IDLE;
                end
            endcase
        end
    end

    // -------------------------------------------------------------------------
    // Portable clog2
    // -------------------------------------------------------------------------
    function integer clog2;
        input integer value;
        integer v;
        begin
            v = value - 1;
            clog2 = 0;
            while (v > 0) begin
                v = v >> 1;
                clog2 = clog2 + 1;
            end
        end
    endfunction

endmodule
