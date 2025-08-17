package com.helper.library.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AladinItemDto {
    private String title;
    private String author;
    private String pubDate;
    private String description;
    private String isbn;
    private String isbn13;
    private int priceSales;
    private int priceStandard;
    private String cover;
    private long categoryId;
    private String categoryName;
    private String publisher;
    private String link;

    @JsonProperty("subInfo")
    private AladinSubInfoDto subInfo;
}
