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
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.BEL;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;

public class Main {

    protected static String rootDir;
    protected static String deviceStr;
    protected static String synthesizedDcp;
    protected static FileWriter writer;

    public static void main(String[] args) throws IOException {
        rootDir = args[0]; // the repository directory
        deviceStr = args[1]; // device name (ex: xczu3eg-sbva484-1-e)
        synthesizedDcp = rootDir + "/outputs/checkpoints/synthesized.dcp";
        System.out.println("Synthesized .dcp file location: " + synthesizedDcp);
        Design design = Design.readCheckpoint(synthesizedDcp);
        Device device = Device.getDevice(deviceStr);
        EDIFNetlist netlist = design.getNetlist();

        Map<String, List<EDIFHierCellInst>> ehciGroups = groupEDIFHierCellInsts(design);
        printUniqueEDIFCellTypes(ehciGroups);

        Map<String, EDIFCellInst> eciMap = netlist.generateCellInstMap();
        printCellInstMap(eciMap);

        printUniqueSiteTypes(device, false);

        ImageMaker imageMaker = new ImageMaker(device);
        imageMaker.construct2DSiteArray();
        imageMaker.construct2DSiteArrayImage();
        imageMaker.exportImage(rootDir + "/outputs/device.png");

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

    public static void printCellInstMap(Map<String, EDIFCellInst> eciMap) throws IOException {
        writer = new FileWriter(rootDir + "/outputs/edifCellInstMap.txt");
        writer.write("\n\nPrinting EDIFCellInst Map...");
        for (Map.Entry<String, EDIFCellInst> entry : eciMap.entrySet()) {
            EDIFCellInst eci = entry.getValue();
            String s1 = String.format(
                "\n\tType: %-20s Name: %-100s", eci.getCellType().getName(), entry.getKey());
            writer.write(s1);
        }
        writer.close();
    }

    public static Set<SiteTypeEnum> printUniqueSiteTypes(Device device, boolean showBELs) throws IOException {
        FileWriter writer = new FileWriter(rootDir + "/outputs/DeviceUniqueSites.txt");
        writer.write("\nPrinting unique sites in the device: ");
        Site[] sites = device.getAllSites();
        Set<SiteTypeEnum> uniqueSiteTypes = new HashSet<>();
        List<Site> uniqueSites = new ArrayList<>();
        for (Site site : sites) {
            if (uniqueSiteTypes.add(site.getSiteTypeEnum())) {
                uniqueSites.add(site);
            }
        }
        writer.write("\nNunmber of unique site types: " + uniqueSites.size());
        printSiteArray(writer, uniqueSites.toArray(new Site[0]), showBELs);
        writer.close();
        return uniqueSiteTypes;
    }

    public static void printSiteArray(FileWriter writer, Site[] sites, boolean showBELs) throws IOException {
        if (sites.length == 0)
            writer.write("\n\tEmpty Site Array.");
        for (Site site : sites) {
            String s1 = String.format(
                "\n\tSiteType: %-30s Example SiteName: %-40s ", site.getSiteTypeEnum(), site.getName());
            writer.write(s1);
            if (showBELs == true) {
                BEL[] bels = site.getBELs();
                printBELArray(writer, bels);
            }
        }
    }

    public static void printBELArray(FileWriter writer, BEL[] bels) throws IOException {
        if (bels.length == 0)
            writer.write("\n\t\tEmpty BEL Array.");
        else
            writer.write("\n\tBELs: ");
        int word_count = 0;
        writer.write("\n\t\t");
        for (BEL bel : bels) {
            writer.write(bel.getName() + " ");
            word_count++;
            if (word_count == 8) {
                writer.write("\n\t\t");
                word_count = 0;
            }
        }
        writer.write("\n");
    }

}
