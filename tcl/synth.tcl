# set design "fir_filter"

set root_dir [lindex $argv 0]
set design [lindex $argv 1]
set top_level [lindex $argv 2]
set top_params [join [lrange $argv 3 end] " "]

set synthesized_dcp "$root_dir/outputs/checkpoints/synthesized.dcp" 

set src_dir "$root_dir/hdl/verilog/$design/src"
set verif_dir "$root_dir/hdl/verilog/$design/verif"
set xdc_dir "$root_dir/hdl/verilog/$design/constrs"

# Read in source files
set src_files [glob -nocomplain -directory $src_dir {*.sv}]
foreach file $src_files {
    puts "reading: $file"
    # read_verilog $file
    if {[string match *.sv $file]} {
        read_verilog -sv $file
    } else {
        read_verilog $file
    }
}

# read in constraints file
set xdc_file $xdc_dir/constraints.xdc
read_xdc $xdc_file

puts "synth.tcl: Received HDL top level parameters: $top_params"

# set cmd "synth_design -mode out_of_context -part xc7z020clg400-1 -fsm_extraction user_encoding -top top_level $top_params"
# set cmd "synth_design -mode out_of_context -part xc7z020clg400-1 -top $design $top_params"
set cmd "synth_design -mode out_of_context -part xczu3eg-sbva484-1-e -top $top_level $top_params"
puts "synth.tcl: Running command: $cmd"

# execute synth_design command
eval $cmd

# opt_design

write_checkpoint -force $synthesized_dcp

exit
