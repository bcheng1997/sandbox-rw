package com.bcheng.placer;

import java.awt.Graphics2D;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.EnumMap;
import java.util.Comparator;
import java.util.stream.Collectors;


import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;

public class ImageMaker {

    Design design;
    Device device;

    private Set<SiteTypeEnum> uniqueSiteTypes;
    private Map<SiteTypeEnum, List<Site>> allSites;
    private Site[][] siteArray;
    private SiteInst[][] siteInstArray;

    private int x_low, y_low;
    private int width, height;
    private final int scale = 3; // hard-coded
    private List<Pair<Net, Float>> netlistCosts;
    private float highest_cost = 0;
    private float lowest_cost = Float.MAX_VALUE;

    private BufferedImage image;

    private final Map<SiteTypeEnum, Color> siteTypeColors;
    private final Map<SiteTypeEnum, Color> placementColors;

    // Similar SiteTypes will share similar colors
    private enum SiteGroup {
        SLICE,
        DSP,
        BRAM,
        FIFO,
        CLOCK_BUFFER,
        IO,
        SERDES_PHY,
        CLOCKING,
        CONFIG,
        PROCESSOR,
        SYSTEM_MONITOR,
        INTERCONNECT,
        OTHER
    }

    private static final Map<SiteGroup, Float> GROUP_HUES = Map.ofEntries(
            Map.entry(SiteGroup.SLICE,          0.55f), // blue/cyan
            Map.entry(SiteGroup.DSP,            0.28f), // green
            Map.entry(SiteGroup.BRAM,           0.83f), // magenta
            Map.entry(SiteGroup.FIFO,           0.95f), // red/pink
            Map.entry(SiteGroup.CLOCK_BUFFER,   0.14f), // yellow/orange
            Map.entry(SiteGroup.IO,             0.08f), // orange/brown
            Map.entry(SiteGroup.SERDES_PHY,     0.72f), // violet
            Map.entry(SiteGroup.CLOCKING,       0.18f), // yellow-green
            Map.entry(SiteGroup.CONFIG,         0.00f), // red
            Map.entry(SiteGroup.PROCESSOR,      0.62f), // deep blue
            Map.entry(SiteGroup.SYSTEM_MONITOR, 0.45f), // teal
            Map.entry(SiteGroup.INTERCONNECT,   0.02f), // red-orange
            Map.entry(SiteGroup.OTHER,          0.00f)  // gray fallback
    );

    private static SiteGroup classifySiteType(SiteTypeEnum siteType) {
        String name = siteType.name();
        if (name.startsWith("SLICE"))
            return SiteGroup.SLICE;
        if (name.contains("DSP"))
            return SiteGroup.DSP;
        if (name.contains("FIFO"))
            return SiteGroup.FIFO;
        if (name.contains("RAMB") || name.contains("BRAM"))
            return SiteGroup.BRAM;
        if (name.startsWith("BUF") || name.contains("BUFG") || name.contains("GCLK"))
            return SiteGroup.CLOCK_BUFFER;
        if (name.contains("IOB") || name.contains("IOLOGIC") || name.contains("HPIO")
                || name.contains("HDIO") || name.contains("IO_")) {
            return SiteGroup.IO;
        }
        if (name.contains("BITSLICE") || name.contains("XIPHY") || name.contains("RIU"))
            return SiteGroup.SERDES_PHY;
        if (name.contains("PLL") || name.contains("MMCM"))
            return SiteGroup.CLOCKING;
        if (name.contains("CONFIG") || name.contains("CFG"))
            return SiteGroup.CONFIG;
        if (name.contains("PS7") || name.contains("PS8"))
            return SiteGroup.PROCESSOR;
        if (name.contains("SYSMON"))
            return SiteGroup.SYSTEM_MONITOR;
        if (name.contains("SWITCH") || name.contains("FEEDTHROUGH"))
            return SiteGroup.INTERCONNECT;
        return SiteGroup.OTHER;
    }

    public static Map<SiteTypeEnum, Color> generateSiteTypeColors(Set<SiteTypeEnum> siteTypes) {
        return generateSiteTypeColors(siteTypes, false);
    }

    public static Map<SiteTypeEnum, Color> generatePlacementColors(Set<SiteTypeEnum> siteTypes) {
        return generateSiteTypeColors(siteTypes, true);
    }

    private static Map<SiteTypeEnum, Color> generateSiteTypeColors(
            Set<SiteTypeEnum> siteTypes,
            boolean activePlacementColors
    ) {
        Map<SiteTypeEnum, Color> colors = new EnumMap<>(SiteTypeEnum.class);
        Map<SiteGroup, List<SiteTypeEnum>> groupedTypes = siteTypes.stream()
                .sorted(Comparator.comparing(Enum::name))
                .collect(Collectors.groupingBy(
                        ImageMaker::classifySiteType,
                        () -> new EnumMap<>(SiteGroup.class),
                        Collectors.toList()
                ));
        for (Map.Entry<SiteGroup, List<SiteTypeEnum>> entry : groupedTypes.entrySet()) {
            SiteGroup group = entry.getKey();
            List<SiteTypeEnum> types = entry.getValue();
            for (int i = 0; i < types.size(); i++) {
                colors.put(types.get(i), colorForGroupIndex(group, i, types.size(), activePlacementColors));
            }
        }
        return colors;
    }

    private static Color colorForGroupIndex(
            SiteGroup group,
            int index,
            int groupSize,
            boolean active
    ) {
        if (group == SiteGroup.OTHER) {
            float brightness = active ? 0.90f : 0.35f;
            float step = groupSize <= 1 ? 0.0f : 0.35f * index / (groupSize - 1);
            return Color.getHSBColor(0.0f, 0.0f, brightness - step);
        }
        float baseHue = GROUP_HUES.get(group);
        // spread similar site types slightly around the group hue.
        float hueOffset = groupSize <= 1
                ? 0.0f
                : ((index / (float) (groupSize - 1)) - 0.5f) * 0.06f;
        float hue = wrapHue(baseHue + hueOffset);
        float saturation = active ? 0.95f : 0.45f;
        float brightness = active ? 1.00f : 0.45f;
        return Color.getHSBColor(hue, saturation, brightness);
    }

    private static float wrapHue(float hue) {
        while (hue < 0.0f)
            hue += 1.0f;
        while (hue > 1.0f)
            hue -= 1.0f;
        return hue;
    }

    public ImageMaker(Device device) throws IOException {
        this.design = null;
        this.device = device;
        this.uniqueSiteTypes = new HashSet<>();
        this.allSites = new HashMap<>();
        initSites();
        this.siteTypeColors = generateSiteTypeColors(uniqueSiteTypes);
        this.placementColors = generatePlacementColors(uniqueSiteTypes);
        this.netlistCosts = new ArrayList<>();
        this.image = new BufferedImage(scale * width, scale * height, BufferedImage.TYPE_INT_RGB);
    }

    public ImageMaker(Design design) throws IOException {
        this.design = design;
        this.device = design.getDevice();
        this.uniqueSiteTypes = new HashSet<>();
        this.allSites = new HashMap<>();
        initSites();
        this.siteTypeColors = generateSiteTypeColors(uniqueSiteTypes);
        this.placementColors = generatePlacementColors(uniqueSiteTypes);
        this.netlistCosts = new ArrayList<>();
        evaluateNetlist();
        this.image = new BufferedImage(scale * width, scale * height, BufferedImage.TYPE_INT_RGB);
    }

    private void initSites() {
        for (Site site : device.getAllSites()) {
            SiteTypeEnum siteType = site.getSiteTypeEnum();
            if (uniqueSiteTypes.add(siteType)) {
                allSites.put(siteType, new ArrayList<>());
            }
            allSites.get(siteType).add(site);
        }
        int x_high = 0, y_high = 0;
        int x_low = Integer.MAX_VALUE, y_low = Integer.MAX_VALUE;
        for (Map.Entry<SiteTypeEnum, List<Site>> entry : this.allSites.entrySet()) {
            for (Site site : entry.getValue()) {
                int site_x = site.getRpmX();
                if (site_x > x_high)
                    x_high = site_x;
                if (site_x < x_low)
                    x_low = site_x;
                int site_y = site.getRpmY();
                if (site_y > y_high)
                    y_high = site_y;
                if (site_y < y_low)
                    y_low = site_y;
            }
        }
        this.x_low = x_low;
        this.y_low = y_low;
        this.width = x_high - x_low + 1;
        this.height = y_high - y_low + 1;
        this.siteArray = new Site[width][height];
    }

    public void renderAll() throws IOException {
        construct2DSiteArray();
        construct2DSiteArrayImage();
        construct2DPlacementArray();
        overlayNetsOnImage();
        overlayPlacementOnImage();
    }

    public void construct2DSiteArray() throws IOException {
        for (Map.Entry<SiteTypeEnum, List<Site>> entry : this.allSites.entrySet()) {
            for (Site site : entry.getValue()) {
                int x = site.getRpmX() - x_low;
                int y = site.getRpmY() - y_low;
                this.siteArray[x][y] = site;
            }
        }
    }

    public Site[][] get2DSiteArray() throws IOException {
        return this.siteArray;
    }

    public void construct2DPlacementArray() throws IOException {
        this.siteInstArray = new SiteInst[width][height];
        for (SiteInst si : this.design.getSiteInsts()) {
            Site site = si.getSite();
            if (site == null)
                continue;
            int x = site.getRpmX() - x_low;
            int y = site.getRpmY() - y_low;
            this.siteInstArray[x][y] = si;
        }
    }

    public void construct2DSiteArrayImage() {
        final int backgroundRgb = Color.BLACK.getRGB();
        // final int defaultRgb = Color.DARK_GRAY.getRGB();

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Site site = siteArray[x][y];

                int borderRgb = backgroundRgb;
                if (site != null) {
                    // System.out.println("Coloring Site: " + site.getSiteTypeEnum());
                    Color color = siteTypeColors.get(site.getSiteTypeEnum());
                    borderRgb = color.getRGB();
                }

                drawSiteTile(
                        x * scale,
                        (height - 1 - y) * scale,
                        borderRgb,
                        backgroundRgb);
            }
        }
    }

    private void drawSiteTile(int pixelX, int pixelY, int borderRgb, int interiorRgb) {
        for (int dx = 0; dx < scale; dx++) {
            for (int dy = 0; dy < scale; dy++) {

                boolean border =
                        dx == 0 ||
                        dx == scale - 1 ||
                        dy == 0 ||
                        dy == scale - 1;

                image.setRGB(
                        pixelX + dx,
                        pixelY + dy,
                        border ? borderRgb : interiorRgb);
            }
        }
    }

    public void overlayPlacementOnImage() throws IOException {
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                SiteInst si = siteInstArray[x][y];
                if (si == null)
                    continue;

                SiteTypeEnum type = si.getSiteTypeEnum();
                Color c = placementColors.getOrDefault(type, Color.DARK_GRAY);
                int destY = (height - 1 - y) * scale;
                int destX = x * scale;

                for (int dx = 0; dx < scale; dx++) {
                    for (int dy = 0; dy < scale; dy++) {
                        image.setRGB(destX + dx, destY + dy, c.getRGB());
                    }
                }
            }
        }
    }

    private void evaluateNetlist() {
        for (Net net : design.getNets()) {
            if (net.isStaticNet())
                continue;
            SitePinInst src = net.getSource();
            if (src == null) { // SPI is null if the net is purely intrasite
                continue;
            }
            List<SitePinInst> sinks = net.getSinkPins();
            Site srcSite = src.getSite();
            if (srcSite == null)
                continue; // sink has not been placed yet
            float cost = 0;
            for (SitePinInst sink : sinks) {
                Site sinkSite = sink.getSite();
                if (sinkSite == null)
                    continue; // sink has not been placed yet
                cost = cost + srcSite.getTile().getTileManhattanDistance(sinkSite.getTile());
            }
            if (cost < lowest_cost)
                lowest_cost = cost;
            if (cost > highest_cost)
                highest_cost = cost;
            netlistCosts.add(new Pair<Net, Float>(net, cost));
        }
        netlistCosts.sort((cost1, cost2) -> Float.compare(cost1.value(), cost2.value()));
    }

    public void overlayNetsOnImage() {
        Graphics2D g2d = image.createGraphics();
        try {
            g2d.setStroke(new BasicStroke(1));
            for (Pair<Net, Float> pair : netlistCosts) {
                Net net = pair.key();
                float cost = pair.value();
                SitePinInst src = net.getSource();
                List<SitePinInst> sinks = net.getSinkPins();

                float ratio = Math.min(cost, highest_cost) / highest_cost;
                int redValue = (int) (0x80 + ratio * (0xFF - 0x80)); // range: 0x40..0xFF
                Color scaledRed = new Color(redValue, 0, 0);
                g2d.setColor(scaledRed);

                Site srcSite = src.getSite();
                for (SitePinInst sink : sinks) {
                    Site sinkSite = sink.getSite();
                    if (sinkSite == null)
                        continue;
                    int sink_x = sinkSite.getRpmX() - x_low;
                    int sink_y = sinkSite.getRpmY() - y_low;
                    int sink_center_x = sink_x * scale + scale / 2;
                    int sink_center_y = (height - 1 - sink_y) * scale + scale / 2;

                    int src_x = srcSite.getRpmX() - x_low;
                    int src_y = srcSite.getRpmY() - y_low;
                    int src_center_x = src_x * scale + scale / 2;
                    int src_center_y = (height - 1 - src_y) * scale + scale / 2;

                    g2d.drawLine(src_center_x, src_center_y, sink_center_x, sink_center_y);
                }
            }
        } finally {
            g2d.dispose();
        }
    }

    public void exportImage(String fileName) throws IOException {
        String fileType = "png";
        File outputFile = new File(fileName);
        ImageIO.write(image, fileType, outputFile);
    }

}
