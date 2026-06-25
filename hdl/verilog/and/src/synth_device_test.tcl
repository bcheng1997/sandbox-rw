set TOP top
set SRC top.v
set parts {
    xc7z020clg400-1
    xcku040-ffva1156-2-e
    xcku060-ffva1156-2-e
    xczu3eg-sbva484-1-e
    xcvc1902-vsva2197-2MP-e-S
}
set results {}

foreach p $parts {
    puts "\n==== Testing $p ===="

    close_project -quiet

    set status "UNKNOWN"
    set detail ""

    if {[catch {
        create_project -in_memory -part $p
        read_verilog $SRC
        synth_design -mode out_of_context -part $p -top $TOP
    } err]} {
        set detail $err

        if {[string match -nocase "*No parts matched*" $err] ||
            [string match -nocase "*Invalid option value specified for '-part'*" $err] ||
            [string match -nocase "*does not exist*" $err]} {
            set status "PART_NOT_FOUND"
        } elseif {[string match -nocase "*valid license was not found*" $err] ||
                  [string match -nocase "*license*" $err]} {
            set status "LICENSE_NOT_FOUND"
        } else {
            set status "SYNTH_FAILED"
        }
    } else {
        set status "SYNTH_OK"
        set detail "Synthesis completed successfully"
    }

    lappend results [list $p $status $detail]
}

puts "\n================ DEVICE SYNTHESIS ACCESS SUMMARY ================"
puts [format "%-45s %-20s %s" "PART" "STATUS" "DETAIL"]
puts [string repeat "-" 100]

foreach r $results {
    lassign $r p status detail

    set short_detail [string map {"\n" " " "\r" " "} $detail]
    if {[string length $short_detail] > 80} {
        set short_detail "[string range $short_detail 0 76]..."
    }

    puts [format "%-45s %-20s %s" $p $status $short_detail]
}

puts "=================================================================="
