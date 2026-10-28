package com.astahpromcp.tool.visualization;

import net.sourceforge.plantuml.security.SecurityProfile;
import net.sourceforge.plantuml.security.SecurityUtils;

import java.util.function.Supplier;

public final class PlantumlSandbox {
    
    // The setting PlantUML reads its security profile from
    static final String PROFILE_SETTING = "PLANTUML_SECURITY_PROFILE";
    
    // Whether this class has already made PlantUML read its profile
    private static boolean profileRead;
    
    private PlantumlSandbox() {
    }
    
    // Make PlantUML run with the SANDBOX profile, and throw IllegalStateException if it runs with any other.
    public static synchronized void ensure() {
        if (!profileRead) {
            withSandboxSetting(SecurityUtils::getSecurityProfile);
            profileRead = true;
        }
        requireSandbox(SecurityUtils.getSecurityProfile());
    }
    
    // Run the read with the profile setting set to SANDBOX, then put the setting back as it was.
    static <T> T withSandboxSetting(Supplier<T> read) {
        String previous = System.getProperty(PROFILE_SETTING);
        System.setProperty(PROFILE_SETTING, SecurityProfile.SANDBOX.name());
        try {
            return read.get();
            
        } finally {
            if (previous == null) {
                System.clearProperty(PROFILE_SETTING);
            } else {
                System.setProperty(PROFILE_SETTING, previous);
            }
        }
    }
    
    // Refuse every profile other than SANDBOX
    static void requireSandbox(SecurityProfile profile) {
        if (profile != SecurityProfile.SANDBOX) {
            throw new IllegalStateException("PlantUML rendering is refused because PlantUML runs with the " + profile
                    + " security profile instead of SANDBOX, which could let the code read local files and URLs.");
        }
    }
}
