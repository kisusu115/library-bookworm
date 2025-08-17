package com.helper.library.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AladinSubInfoDto {
    private String subTitle;
    private String originalTitle;
    private int itemPage;
}
