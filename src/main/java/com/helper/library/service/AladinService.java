package com.helper.library.service;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.helper.library.dto.AladinApiResponseDto;
import com.helper.library.dto.AladinItemDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AladinService {

    private final RestTemplate restTemplate;
    private final XmlMapper xmlMapper;

    public Optional<AladinItemDto> searchBookByIsbn(String isbn, String ttbkey) {
        if (isbn == null || isbn.trim().isEmpty()) {
            return Optional.empty();
        }
        String url = "https://www.aladin.co.kr/ttb/api/ItemLookUp.aspx?ttbkey=" + ttbkey +
                     "&itemIdType=ISBN13&ItemId=" + isbn +
                     "&output=xml&Version=20131101&OptResult=packing,subinfo";
        try {
            String response = restTemplate.getForObject(url, String.class);

            if (response != null && response.contains("<error>")) {
                log.warn("Aladin API returned an error for ISBN {}. Response: {}", isbn, response);
                return Optional.empty();
            }

            AladinApiResponseDto apiResponse = xmlMapper.readValue(response, AladinApiResponseDto.class);

            if (apiResponse != null && apiResponse.getItem() != null && !apiResponse.getItem().isEmpty()) {
                log.info("Successfully found book for ISBN: {}", isbn);
                return Optional.of(apiResponse.getItem().get(0));
            } else {
                log.warn("No book found for ISBN: {}. The API returned a valid but empty response.", isbn);
                return Optional.empty();
            }
        } catch (MismatchedInputException e) {
            log.warn("Failed to parse Aladin API response for ISBN {}. The response might be an error message or malformed XML.", isbn, e);
        } catch (Exception e) {
            log.error("An unexpected error occurred while calling Aladin API for ISBN {}.", isbn, e);
        }
        return Optional.empty();
    }
}
