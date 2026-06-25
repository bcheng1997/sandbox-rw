package com.bcheng.placer;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;
import java.io.FileWriter;
import java.io.IOException;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;

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
        EDIFNetlist netlist = design.getNetlist();

        Map<String, List<EDIFHierCellInst>> ehciGroups = groupEDIFHierCellInsts(design);
        printUniqueEDIFCellTypes(ehciGroups);

        Map<String, EDIFCellInst> eciMap = netlist.generateCellInstMap();
        printCellInstMap(eciMap);

        return;
    }

    public static Map<String, List<EDIFHierCellInst>> groupEDIFHierCellInsts(Design design) {
        Map<String, List<EDIFHierCellInst>> ehciGroups = new HashMap<>();
        // Organize EDIFHierCellInsts into "groups", where group labels are:
        // RAMB18Ex, DSP48Ex, CARRY4, FDRE, FDSE, LUT (LUT2-6 all in one group), etc.
        for (EDIFHierCellInst ehci : design.getNetlist().getAllLeafHierCellInstances()) {
            String cellType = ehci.getInst().getCellType().getName();
            // Group all LUT variants together
            if (cellType.contains("LUT"))
                cellType = "LUT";
            ehciGroups.computeIfAbsent(cellType, k -> new ArrayList<>()).add(ehci);
        }
        return ehciGroups;
    }

    public static void printCellInstMap(Map<String, EDIFCellInst> eciMap) throws IOException {
        writer = new FileWriter(rootDir + "/outputs/edifCellInstMap.txt");
        writer.write("\n\nPrinting EDIFCellInst Map...");
        for (Map.Entry<String, EDIFCellInst> entry : eciMap.entrySet()) {
            EDIFCellInst eci = entry.getValue();

            String s1 = String.format("\n\tType: %-20s Name: %-100s", eci.getCellType().getName(), entry.getKey());

            writer.write(s1);
        }
        writer.close();
    }

    public static void printUniqueEDIFCellTypes(
            Map<String, List<EDIFHierCellInst>> ehciGroups) throws IOException {
        writer = new FileWriter(rootDir + "/outputs/uniqueEdifCellTypes.txt");
        writer.write("\n\nSet of all Unique EDIF Cell Types... (" + ehciGroups.size() + ")");
        for (String edifCellType : ehciGroups.keySet()) {
            writer.write("\n\t" + edifCellType);
        }
        writer.write("\n\nPrinting EDIFCells By Type...");
        for (Map.Entry<String, List<EDIFHierCellInst>> entry : ehciGroups.entrySet()) {
            // Print name of cell type and number of cells in that list
            writer.write("\n\n" + entry.getKey() + " Cells (" + entry.getValue().size() + "):");
            // Get list of cell instances belonging to this cell type
            List<EDIFCellInst> ecis = entry.getValue().stream()
                    .map(EDIFHierCellInst::getInst)
                    .collect(Collectors.toList());
            if (ecis.size() > 0) {
                String cellType = ecis.get(0).getCellType().getName();
                writer.write("\n\tPrinting all EDIFCellInsts of type " + cellType + "... (" + ecis.size() + ")");
                writer.write("\n\t(Cell_Inst_Type: Cell_Inst_Name)");
            }
            for (EDIFCellInst eci : ecis) {
                writer.write("\n\t\t" + eci.getCellType() + ": " + eci.getName());
                // Collection<EDIFPortInst> epis = eci.getPortInsts();
                // for (EDIFPortInst epi : epis)
                //     writer.write("\n\t\t\t" + epi.getFullName());
            }
        }
        writer.close();
    }

}
