#!/bin/bash

# ==========================================================================
# Update ROOT_DIR, DESIGN, TOP_LEVEL, XILINX_VIVADO, and RAPIDWRIGHT_PATH to
# reflect your environment.

# This repository's directory.
ROOT_DIR="/home/bcheng/workspace/dev/sandbox-rw"

# Name of your Verilog project.
DESIGN="fir_filter"

# Name of the top level module of your Verilog project.
TOP_LEVEL="top_level"

# Directories of your Vivado 2025.1.1 and RapidWright installations.
export XILINX_VIVADO=/home/bcheng/workspace/tools/Xilinx/2025.1.1/Vivado
export RAPIDWRIGHT_PATH=/home/bcheng/workspace/tools/RapidWright

# (end of user's variables)
# ==========================================================================

export PATH="$PATH:$XILINX_VIVADO/bin"
export JAVA_HOME=$XILINX_VIVADO/tps/lnx64/jre21.0.1_12
export PATH="$JAVA_HOME/bin:$PATH"
export PATH="$PATH:$RAPIDWRIGHT_PATH/bin"
export CLASSPATH=$RAPIDWRIGHT_PATH/bin:$RAPIDWRIGHT_PATH/jars/*
export _JAVA_OPTIONS=-Xmx32736m

SYNTH_TCL="$ROOT_DIR/tcl/synth.tcl"
RTL_TCL="$ROOT_DIR/tcl/rtl.tcl"
PLACE_TCL="$ROOT_DIR/tcl/place.tcl"
ROUTE_TCL="$ROOT_DIR/tcl/route.tcl"
SIM_TCL="$ROOT_DIR/tcl/sim.tcl"

DESIGN_DIR="$ROOT_DIR/hdl/verilog/${DESIGN}"
TOP_PARAMS_FILE="$DESIGN_DIR/parameters_top_level.txt"
XELAB_TOP_PARAMS=""
SYNTH_TOP_PARAMS=""

start_stage=${1:-all} # Use first argument or defaults to all
num_args=$#           # Number of arguments into script

check_exit_status() {
    if [ $? -ne 0 ]; then
        echo "$1 failed."
        exit 1
    fi
}

# Vivado Synthesis Stage
if [ "$start_stage" == "synth" ]; then
    echo "Running Vivado synthesis..."
    vivado -mode batch -source $SYNTH_TCL -nolog -nojournal -tclargs $ROOT_DIR $DESIGN $TOP_LEVEL $SYNTH_TOP_PARAMS
    check_exit_status "Vivado synthesis"
    echo "Finished Vivado synthesis."
fi

# Vivado RTL Synthesis Stage
if [ "$start_stage" == "rtl" ]; then
    echo "Running Vivado RTL synthesis..."
    cd $ROOT_DIR
    vivado -mode batch -source $RTL_TCL -nolog -nojournal -tclargs $ROOT_DIR $DESIGN $TOP_LEVEL $SYNTH_TOP_PARAMS
    check_exit_status "Vivado RTL synthesis"
    echo "Finished Vivado RTL synthesis. Starting GUI."
fi

# Java Compile Stage
if [ "$start_stage" == "compile" ]; then
    echo "Building Java project with Gradle..."
    rm -rf "$ROOT_DIR/outputs/placers/*"
    cd $ROOT_DIR/java
    gradle build
    check_exit_status "Java compile"
    echo "Finished Java compile."
fi

# Java Placement Stage
if [ "$start_stage" == "place" ]; then
    rm $ROOT_DIR/outputs/placers/* -r
    echo "Running Java placement with Gradle..."
    cd $ROOT_DIR/java
    gradle run --args="$ROOT_DIR"
    check_exit_status "Java place"
    cd $ROOT_DIR
    echo "Finished Java place."
fi

# Vivado Route Stage
if [ "$start_stage" == "route" ]; then
    echo "Running Vivado route..."
    vivado -mode batch -source $ROUTE_TCL -nolog -nojournal -tclargs $ROOT_DIR
    check_exit_status "Vivado route"
    echo "Finished Vivado route."
fi
