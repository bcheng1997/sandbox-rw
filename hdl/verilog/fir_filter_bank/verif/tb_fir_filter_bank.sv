`timescale 1ns/1ps

module tb_fir_filter_bank;

    localparam integer NUM_CHANNELS  = 4;
    localparam integer TAPS          = 8;
    localparam integer PARALLEL_MACS = 2;
    localparam integer DATA_W        = 16;
    localparam integer COEFF_W       = 16;
    localparam integer ACC_W         = 40;
    localparam integer FIFO_DEPTH    = 64;
    localparam integer CH_W          = 2;
    localparam integer TAP_W         = 3;

    reg clk = 1'b0;
    reg rst = 1'b1;

    always #5 clk = ~clk;

    reg signed [DATA_W-1:0] s_data;
    reg [CH_W-1:0]          s_chan;
    reg                     s_valid;
    wire                    s_ready;
    reg                     s_last;

    reg                     coeff_we;
    reg [CH_W-1:0]          coeff_chan;
    reg [TAP_W-1:0]         coeff_tap;
    reg signed [COEFF_W-1:0] coeff_data;

    wire signed [DATA_W-1:0] m_data;
    wire [CH_W-1:0]          m_chan;
    wire                     m_valid;
    reg                      m_ready;
    wire                     m_last;

    wire [31:0] stat_packet_count;
    wire [31:0] stat_sample_count;
    wire [31:0] stat_overflow_count;
    wire [31:0] stat_checksum;

    fir_filter_bank #(
        .NUM_CHANNELS(NUM_CHANNELS),
        .TAPS(TAPS),
        .PARALLEL_MACS(PARALLEL_MACS),
        .DATA_W(DATA_W),
        .COEFF_W(COEFF_W),
        .ACC_W(ACC_W),
        .FIFO_DEPTH(FIFO_DEPTH),
        .CH_W(CH_W),
        .TAP_W(TAP_W)
    ) dut (
        .clk(clk),
        .rst(rst),
        .s_data(s_data),
        .s_chan(s_chan),
        .s_valid(s_valid),
        .s_ready(s_ready),
        .s_last(s_last),
        .coeff_we(coeff_we),
        .coeff_chan(coeff_chan),
        .coeff_tap(coeff_tap),
        .coeff_data(coeff_data),
        .m_data(m_data),
        .m_chan(m_chan),
        .m_valid(m_valid),
        .m_ready(m_ready),
        .m_last(m_last),
        .stat_packet_count(stat_packet_count),
        .stat_sample_count(stat_sample_count),
        .stat_overflow_count(stat_overflow_count),
        .stat_checksum(stat_checksum)
    );

    integer ch;
    integer tap;
    integer n;

    initial begin
        s_data     = 0;
        s_chan     = 0;
        s_valid    = 0;
        s_last     = 0;
        coeff_we   = 0;
        coeff_chan = 0;
        coeff_tap  = 0;
        coeff_data = 0;
        m_ready    = 1;

        repeat (10) @(posedge clk);
        rst = 0;

        // Program simple averaging-ish FIR coefficients.
        // coeff[0..7] = 1, so output is recent running sum.
        for (ch = 0; ch < NUM_CHANNELS; ch = ch + 1) begin
            for (tap = 0; tap < TAPS; tap = tap + 1) begin
                write_coeff(ch[CH_W-1:0], tap[TAP_W-1:0], 16'sd1);
            end
        end

        // Send a short packet on each channel.
        for (n = 0; n < 32; n = n + 1) begin
            send_sample(n[CH_W-1:0], n + 1, (n % 8) == 7);
        end

        repeat (500) @(posedge clk);

        $display("packet_count   = %0d", stat_packet_count);
        $display("sample_count   = %0d", stat_sample_count);
        $display("overflow_count = %0d", stat_overflow_count);
        $display("checksum       = %0d", stat_checksum);

        $finish;
    end

    task write_coeff;
        input [CH_W-1:0] ch_i;
        input [TAP_W-1:0] tap_i;
        input signed [COEFF_W-1:0] val_i;
        begin
            @(posedge clk);
            coeff_we   <= 1'b1;
            coeff_chan <= ch_i;
            coeff_tap  <= tap_i;
            coeff_data <= val_i;
            @(posedge clk);
            coeff_we   <= 1'b0;
        end
    endtask

    task send_sample;
        input [CH_W-1:0] ch_i;
        input signed [DATA_W-1:0] val_i;
        input last_i;
        begin
            @(posedge clk);
            s_data  <= val_i;
            s_chan  <= ch_i;
            s_last  <= last_i;
            s_valid <= 1'b1;

            while (!s_ready) begin
                @(posedge clk);
            end

            @(posedge clk);
            s_valid <= 1'b0;
            s_last  <= 1'b0;
        end
    endtask

    always @(posedge clk) begin
        if (m_valid && m_ready) begin
            $display("[%0t] OUT ch=%0d data=%0d last=%0b",
                     $time, m_chan, m_data, m_last);
        end
    end

endmodule
