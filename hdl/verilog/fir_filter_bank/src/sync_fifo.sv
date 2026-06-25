`timescale 1ns/1ps

module sync_fifo #(
    parameter integer DATA_W = 64,
    parameter integer DEPTH  = 512,
    parameter integer AW     = clog2(DEPTH)
)(
    input  wire                 clk,
    input  wire                 rst,

    input  wire                 wr_en,
    input  wire [DATA_W-1:0]    din,
    output wire                 full,

    input  wire                 rd_en,
    output wire [DATA_W-1:0]    dout,
    output wire                 empty
);

    (* ram_style = "block" *) reg [DATA_W-1:0] mem [0:DEPTH-1];

    reg [AW-1:0] wr_ptr;
    reg [AW-1:0] rd_ptr;
    reg [AW:0]   count;

    reg [DATA_W-1:0] dout_r;

    assign dout  = dout_r;
    assign full  = (count == DEPTH);
    assign empty = (count == 0);

    always @(posedge clk) begin
        if (rst) begin
            wr_ptr <= {AW{1'b0}};
            rd_ptr <= {AW{1'b0}};
            count  <= {(AW+1){1'b0}};
            dout_r <= {DATA_W{1'b0}};
        end else begin
            if (wr_en && !full) begin
                mem[wr_ptr] <= din;
                wr_ptr <= wr_ptr + 1'b1;
            end

            if (rd_en && !empty) begin
                dout_r <= mem[rd_ptr];
                rd_ptr <= rd_ptr + 1'b1;
            end

            case ({wr_en && !full, rd_en && !empty})
                2'b10: count <= count + 1'b1;
                2'b01: count <= count - 1'b1;
                default: count <= count;
            endcase
        end
    end

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
