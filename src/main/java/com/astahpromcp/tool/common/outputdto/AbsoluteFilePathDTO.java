package com.astahpromcp.tool.common.outputdto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record AbsoluteFilePathDTO(
    @JsonPropertyDescription("Absolute file path")
    String absoluteFilePath
) {
}
