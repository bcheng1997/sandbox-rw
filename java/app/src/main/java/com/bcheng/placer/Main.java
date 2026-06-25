package com.bcheng.placer;

import java.util.stream.Collectors;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.io.FileWriter;
import java.io.File;
import java.io.IOException;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFPortInst;

public class Main {

    protected static String rootDir;
    protected static String synthesizedDcp;
    protected static FileWriter writer;

    public static void main(String[] args) throws IOException {
        rootDir = args[0]; // the repository directory
        synthesizedDcp = rootDir + "/outputs/checkpoints/synthesized.dcp";
        System.out.println("Synthesized .dcp file location: " + synthesizedDcp);
        Design design = Design.readCheckpoint(synthesizedDcp);
        Device device = Device.getDevice("xc7z020clg400-1");

        // Create a map to group cells by type
        Map<String, List<EDIFHierCellInst>> EDIFCellGroups = new HashMap<>();
        Set<String> uniqueEdifCellTypes = new HashSet<>();

        // Organize EDIFHierCellInsts into "groups", where group labels are:
        // RAMB18E1, DSP18E1, CARRY4, FDRE, FDSE, LUT (LUT2-6 all in one group), etc.
        for (EDIFHierCellInst ehci : design.getNetlist().getAllLeafHierCellInstances()) {
            String cellType = ehci.getInst().getCellType().getName();
            // group all luts together
            if (cellType.contains("LUT"))
                cellType = "LUT";
            // populate unique cell types
            if (uniqueEdifCellTypes.add(cellType)) // set returns bool
                EDIFCellGroups.put(cellType, new ArrayList<>()); // spawn unique group
            // add cell to corresponding group
            EDIFCellGroups.get(cellType).add(ehci); // add cell to corresponding group
        }

        writer = new FileWriter(rootDir + "/outputs/uniqueEdifCellTypes.txt");
        writer.write("\n\nSet of all Unique EDIF Cell Types... (" + uniqueEdifCellTypes.size() + ")");
        for (String edifCellType : uniqueEdifCellTypes) {
            writer.write("\n\t" + edifCellType);
        }
        writer.write("\nPrinting EDIFCells By Type...");
        for (Map.Entry<String, List<EDIFHierCellInst>> entry : EDIFCellGroups.entrySet()) {
            writer.write("\n\n" + entry.getKey() + " Cells (" + entry.getValue().size() + "):");
            List<EDIFCellInst> cells = entry.getValue().stream()
                    .map(e -> e.getInst())
                    .collect(Collectors.toList());
            printEDIFCellInstList(cells);
        }

    }

    public static void printEDIFCellInstList(List<EDIFCellInst> ecis) throws IOException {
        if (ecis.size() > 0) {
            String cellType = ecis.get(0).getCellType().getName();
            writer.write("\n\tPrinting all EDIFCellInsts of type " + cellType + "... (" + ecis.size() + ")");
        }
        for (EDIFCellInst eci : ecis) {
            writer.write("\n\t\t" + eci.getCellType() + ": " + eci.getName());
            Collection<EDIFPortInst> epis = eci.getPortInsts();
            for (EDIFPortInst epi : epis) {
                writer.write("\n\t\t\t" + epi.getFullName());
            }
        }
    }

}
