# set design "fir_filter"

set root_dir [lindex $argv 0]
set design [lindex $argv 1]
set top_level [lindex $argv 2]
set top_params [join [lrange $argv 3 end] " "]

set rtl_synth "$root_dir/outputs/checkpoints/rtl_synth.dcp" 

set src_dir "$root_dir/hdl/verilog/$design/src"
set verif_dir "$root_dir/hdl/verilog/$design/verif"
set xdc_dir "$root_dir/hdl/verilog/$design/constrs"

# Read in source files
set src_files [concat \
    [glob -nocomplain -directory $src_dir *.v] \
    [glob -nocomplain -directory $src_dir *.sv]]

foreach file $src_files {
    puts "reading: $file"

    if {[string match *.sv $file]} {
        read_verilog -sv $file
    } else {
        read_verilog $file
    }
}


# Read in constraints file
set xdc_file $xdc_dir/constraints.xdc
read_xdc $xdc_file

puts "rtl.tcl: Received parameters: $top_params"

set cmd "synth_design -mode out_of_context -part xc7z020clg400-1 -top $top_level -rtl -rtl_skip_mlo -name rtl_1 $top_params"
puts "rtl.tcl: Running command: $cmd"

# execute synth_design command
eval $cmd

start_gui
