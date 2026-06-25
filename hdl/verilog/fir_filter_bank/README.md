# FIR Filter Bank HDL Placer Stress Test

This is a synthesizable SystemVerilog design intended to stress a heterogeneous FPGA placer.

## Main design

Top module:

```systemverilog
fir_filter_bank
```

Resource pressure:

| Resource | Source |
|---|---|
| LUT | FSMs, muxing, address decode, valid/ready control |
| FF | stream registers, state, pipeline/control registers |
| CARRY | counters, checksum, accumulators, address arithmetic |
| BRAM | coefficient RAM, sample-history RAM, output FIFO |
| DSP | signed multiply-accumulate FIR lanes |

## Suggested synthesis scale

For a small simulation:

```systemverilog
NUM_CHANNELS  = 4
TAPS          = 8
PARALLEL_MACS = 2
FIFO_DEPTH    = 64
```

For a real placer stress test:

```systemverilog
NUM_CHANNELS  = 16
TAPS          = 64
PARALLEL_MACS = 4 or 8
FIFO_DEPTH    = 512
```

For heavier stress:

```systemverilog
NUM_CHANNELS  = 32
TAPS          = 128
PARALLEL_MACS = 8 or 16
FIFO_DEPTH    = 1024
```

## Vivado example

```tcl
read_verilog -sv sync_fifo.sv
read_verilog -sv fir_filter_bank.sv
synth_design -top fir_filter_bank -part xc7z020clg400-1
report_utilization
```

## Simulation example with Icarus Verilog

```bash
iverilog -g2012 -o tb.out sync_fifo.sv fir_filter_bank.sv tb_fir_filter_bank.sv
vvp tb.out
```

## Important note

The design intentionally uses memory shifts and shared MAC scheduling. That gives a balanced mix of fixed-column resources and fabric control rather than simply instantiating a giant array of multipliers.
