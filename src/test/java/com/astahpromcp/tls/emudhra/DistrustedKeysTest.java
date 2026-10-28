package com.astahpromcp.tls.emudhra;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class DistrustedKeysTest {
    
    private static final String KEY_A = "a".repeat(64);
    private static final String KEY_B = "b".repeat(64);
    
    @Test
    void load_ok_listsTheThreeRootsOpenJdkRemovedAndTheEightCompromisedKeys() {
        DistrustedKeys keys = DistrustedKeys.load();
        
        assertEquals(3, keys.roots().size());
        assertEquals(8, keys.intermediates().size());
        // emSign ECC Root CA - G3, which the compromised intermediates chain to
        assertTrue(keys.roots().contains("8d417db2dd8bf5e3084d1e3f196d583849d81bdd4c00c70b9d39369e96b8c782"));
        // EM DV TLS CA - G3B
        assertTrue(keys.intermediates().contains("ebed6475c2c2d80fcb199f3fe8dd6e6b3ce60b77eb15d4e42c3dec0c6f5b5105"));
    }
    
    @Test
    void parse_ok_skipsCommentsAndBlankLinesAndAllowsANameWithSpaces() {
        DistrustedKeys keys = DistrustedKeys.parse(List.of(
                "# a comment",
                "",
                "root " + KEY_A + "    Some Root CA - G1   # trailing comment",
                "intermediate\t" + KEY_B));
        
        assertEquals(Set.of(KEY_A), keys.roots());
        assertEquals(Set.of(KEY_B), keys.intermediates());
    }
    
    @Test
    void parse_ng_rejectsAKeyThatIsNotLowercaseSha256Hex() {
        for (String key : List.of("a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            assertThrows(IllegalArgumentException.class, () -> DistrustedKeys.parse(List.of("root " + key)), key);
        }
    }
    
    @Test
    void parse_ng_rejectsAnUnknownKind() {
        assertThrows(IllegalArgumentException.class, () -> DistrustedKeys.parse(List.of("root " + KEY_A, "leaf " + KEY_B)));
    }
    
    @Test
    void parse_ng_rejectsAKeyListedTwice() {
        assertThrows(IllegalArgumentException.class, () -> DistrustedKeys.parse(List.of("root " + KEY_A, "intermediate " + KEY_A)));
    }
    
    @Test
    void parse_ng_rejectsAListWithoutARoot() {
        assertThrows(IllegalArgumentException.class, () -> DistrustedKeys.parse(List.of("intermediate " + KEY_B)));
    }
}
