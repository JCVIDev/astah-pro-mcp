package com.astahpromcp.tool.projectdiff.engine;

import com.astahpromcp.tool.projectdiff.xmi.XmiIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

// A value that differs between the two sides without any edit, and by how much it may differ before the difference counts as a change. Every other value is compared exactly.
public enum ToleratedDifference {
    
    // The coordinates and sizes of a presentation that differ only in their last digits.
    // A difference this small never comes from an edit, so it is tolerated value by value, even in a presentation that changed otherwise.
    FLOATING_POINT_ROUNDING("floatingPointRounding", true) {
        @Override
        boolean covers(XmiIndex.Element element) {
            return element.tag().startsWith("JUDE:") && element.tag().endsWith("Presentation");
        }
        
        @Override
        boolean tolerates(String path, String before, String after) {
            if (!GEOMETRY_PATH.matcher(path).matches()) {
                return false;
            }
            double[] values = numbers(before, after);
            return values != null
                    && Math.abs(values[0] - values[1]) <= ROUNDING_TOLERANCE * Math.max(1.0, Math.max(Math.abs(values[0]), Math.abs(values[1])));
        }
    },
    
    // The label of a message on a communication diagram: showing the diagram makes Astah measure the label's text with the fonts of the screen and resize the label around its center, by up to 1.6 pixels in width. Tolerated only when nothing else about the label differs, because a label that changed otherwise is resized for that change.
    COMMUNICATION_MESSAGE_LABEL_SIZE("communicationMessageLabelSize", false) {
        @Override
        boolean covers(XmiIndex.Element element) {
            return element.tag().equals("JUDE:LabelPresentation") && element.ownerPath().equals("JUDE:MessageCLPresentation.namePresentation");
        }
        
        @Override
        boolean tolerates(String path, String before, String after) {
            if (!MESSAGE_LABEL_PATHS.contains(path)) {
                return false;
            }
            double[] values = numbers(before, after);
            return values != null && Math.abs(values[0] - values[1]) < 2.0;
        }
    };
    
    // How much two coordinates or sizes may differ by rounding, as a part of the larger of them, or of 1 for values below 1. Far below any edit.
    static final double ROUNDING_TOLERANCE = 1e-9;
    
    // The coordinates (the fields of a location, the points of a line) and the size of a presentation
    private static final Pattern GEOMETRY_PATH = Pattern.compile("@width|@height|(?:.*/)?XMI\\.field\\[\\d+]");
    
    // The size and the position of the label of a message on a communication diagram
    private static final Set<String> MESSAGE_LABEL_PATHS = Set.of("@width", "JUDE:JomtPresentation.location/XMI.field[0]", "JUDE:JomtPresentation.location/XMI.field[1]");
    
    private final String ruleName;
    private final boolean valueByValue;
    
    ToleratedDifference(String ruleName, boolean valueByValue) {
        this.ruleName = ruleName;
        this.valueByValue = valueByValue;
    }
    
    public String ruleName() {
        return ruleName;
    }
    
    // Whether the element is one whose values this covers
    abstract boolean covers(XmiIndex.Element element);
    
    // Whether two values at the given path differ by no more than this allows
    abstract boolean tolerates(String path, String before, String after);
    
    // The tolerated differences that cover an element on both sides, in the order they apply
    static List<ToleratedDifference> covering(XmiIndex.Element before, XmiIndex.Element after) {
        List<ToleratedDifference> differences = new ArrayList<>();
        for (ToleratedDifference difference : values()) {
            if (difference.covers(before) && difference.covers(after)) {
                differences.add(difference);
            }
        }
        return differences;
    }
    
    // Whether a value that differs between the two sides is to be taken as unchanged even in an element that also changed otherwise.
    public static boolean toleratedValue(XmiIndex.Element before, XmiIndex.Element after, String path, String beforeValue, String afterValue) {
        for (ToleratedDifference difference : values()) {
            if (difference.valueByValue && difference.covers(before) && difference.covers(after) && difference.tolerates(path, beforeValue, afterValue)) {
                return true;
            }
        }
        return false;
    }
    
    // The two values as finite numbers, or null when either is not one
    private static double[] numbers(String before, String after) {
        try {
            double a = Double.parseDouble(before);
            double b = Double.parseDouble(after);
            return Double.isFinite(a) && Double.isFinite(b) ? new double[] {a, b} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
