package com.astahpromcp.tool.astah.pro.project;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ProjectDiffStartToolTest {
    
    @Test
    void keyOf_ok_makesOneKeyOfSpellingsOfAWindowsPathThatDifferOnlyInCase() {
        String key = ProjectDiffStartTool.keyOf(Path.of("C:\\dev\\Model\\Project.asta"), true);
        
        assertEquals(key, ProjectDiffStartTool.keyOf(Path.of("c:\\DEV\\model\\project.ASTA"), true));
        assertNotEquals(key, ProjectDiffStartTool.keyOf(Path.of("C:\\dev\\Model\\Other.asta"), true));
    }
    
    @Test
    void keyOf_ok_keepsTheCaseWhereFileNamesTellCaseApart() {
        assertNotEquals(ProjectDiffStartTool.keyOf(Path.of("/work/Model.asta"), false), ProjectDiffStartTool.keyOf(Path.of("/work/model.asta"), false));
    }
}
