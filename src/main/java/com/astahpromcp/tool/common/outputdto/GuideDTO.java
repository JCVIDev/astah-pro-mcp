package com.astahpromcp.tool.common.outputdto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record GuideDTO(
    @JsonPropertyDescription("Guide contents")
    String contents
) {
}
