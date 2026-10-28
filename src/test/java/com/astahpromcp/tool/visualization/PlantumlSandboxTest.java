package com.astahpromcp.tool.visualization;

import net.sourceforge.plantuml.security.SecurityProfile;
import net.sourceforge.plantuml.security.SecurityUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PlantumlSandboxTest {
    
    // PlantUML keeps the profile it reads first
    @BeforeAll
    static void settleProfile() {
        PlantumlSandbox.ensure();
    }
    
    @Test
    void ensure_ok_plantumlRunsWithTheSandboxProfile() {
        assertDoesNotThrow(PlantumlSandbox::ensure);
        assertEquals(SecurityProfile.SANDBOX, SecurityUtils.getSecurityProfile());
    }
    
    @Test
    void requireSandbox_ok_acceptsSandbox() {
        assertDoesNotThrow(() -> PlantumlSandbox.requireSandbox(SecurityProfile.SANDBOX));
    }
    
    @Test
    void requireSandbox_ng_refusesEveryOtherProfile() {
        for (SecurityProfile profile : SecurityProfile.values()) {
            if (profile == SecurityProfile.SANDBOX) {
                continue;
            }
            
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> PlantumlSandbox.requireSandbox(profile), profile.name());
            assertTrue(e.getMessage().contains(profile.name()), e.getMessage());
        }
    }
    
    @Test
    void withSandboxSetting_ok_setsSandboxOnlyWhilePlantumlReadsIt() {
        String original = System.getProperty(PlantumlSandbox.PROFILE_SETTING);
        try {
            System.setProperty(PlantumlSandbox.PROFILE_SETTING, SecurityProfile.INSECURE.name());
            
            String seenDuringRead = PlantumlSandbox.withSandboxSetting(
                    () -> System.getProperty(PlantumlSandbox.PROFILE_SETTING));
            
            assertEquals(SecurityProfile.SANDBOX.name(), seenDuringRead);
            assertEquals(SecurityProfile.INSECURE.name(), System.getProperty(PlantumlSandbox.PROFILE_SETTING));
            
        } finally {
            restoreSetting(original);
        }
    }
    
    @Test
    void withSandboxSetting_ok_leavesNoSettingBehindWhenThereWasNone() {
        String original = System.getProperty(PlantumlSandbox.PROFILE_SETTING);
        try {
            System.clearProperty(PlantumlSandbox.PROFILE_SETTING);
            
            String seenDuringRead = PlantumlSandbox.withSandboxSetting(
                    () -> System.getProperty(PlantumlSandbox.PROFILE_SETTING));
            
            assertEquals(SecurityProfile.SANDBOX.name(), seenDuringRead);
            assertNull(System.getProperty(PlantumlSandbox.PROFILE_SETTING));
            
        } finally {
            restoreSetting(original);
        }
    }
    
    @Test
    void withSandboxSetting_ok_restoresTheSettingWhenTheReadFails() {
        String original = System.getProperty(PlantumlSandbox.PROFILE_SETTING);
        try {
            System.setProperty(PlantumlSandbox.PROFILE_SETTING, SecurityProfile.INSECURE.name());
            
            assertThrows(IllegalStateException.class, () -> PlantumlSandbox.withSandboxSetting(() -> {
                throw new IllegalStateException("read failed");
            }));
            assertEquals(SecurityProfile.INSECURE.name(), System.getProperty(PlantumlSandbox.PROFILE_SETTING));
            
        } finally {
            restoreSetting(original);
        }
    }
    
    private static void restoreSetting(String original) {
        if (original == null) {
            System.clearProperty(PlantumlSandbox.PROFILE_SETTING);
        } else {
            System.setProperty(PlantumlSandbox.PROFILE_SETTING, original);
        }
    }
}
