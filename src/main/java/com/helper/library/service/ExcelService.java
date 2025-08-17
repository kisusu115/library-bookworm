package com.helper.library.service;

import com.helper.library.dto.AladinItemDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelService {

    private final AladinService aladinService;
    private final ExecutorService taskExecutor;

    public List<String> parseIsbnFromExcel(MultipartFile file, String isbnColumn, int startRow) throws IOException {
        List<String> isbns = new ArrayList<>();
        try (InputStream inputStream = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            int isbnColIndex = CellReference.convertColStringToIndex(isbnColumn);

            for (int i = startRow - 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row != null) {
                    Cell cell = row.getCell(isbnColIndex);
                    if (cell != null && cell.getCellType() == CellType.STRING) {
                        String isbn = cell.getStringCellValue().trim();
                        if (!isbn.isEmpty()) {
                            isbns.add(isbn);
                        }
                    } else if (cell != null && cell.getCellType() == CellType.NUMERIC) {
                        String isbn = String.valueOf((long) cell.getNumericCellValue()).trim();
                        if (!isbn.isEmpty()) {
                            isbns.add(isbn);
                        }
                    }
                }
            }
        }
        log.info("Parsed {} ISBNs from Excel file.", isbns.size());
        return isbns;
    }

    public List<String> parseIsbnFromText(String text) {
        List<String> isbns = Arrays.stream(text.split("\r?\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        log.info("Parsed {} ISBNs from text input.", isbns.size());
        return isbns;
    }

    public CompletableFuture<Workbook> createExcelFile(List<String> isbns, String ttbkey, Consumer<Double> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            Workbook workbook = new XSSFWorkbook();
            Sheet sheet = workbook.createSheet("도서 정보");
            createHeaderRow(sheet);

            int total = isbns.size();
            if (total == 0) {
                return workbook;
            }

            AtomicInteger completedCount = new AtomicInteger(0);
            AtomicInteger lastLoggedPercent = new AtomicInteger(0);

            List<CompletableFuture<AladinItemDto>> futures = isbns.stream()
                    .map(isbn -> {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        return CompletableFuture.supplyAsync(() -> aladinService.searchBookByIsbn(isbn, ttbkey).orElse(null), taskExecutor)
                            .whenComplete((item, throwable) -> {
                                int completed = completedCount.incrementAndGet();
                                double progress = (double) completed / total * 100;
                                progressCallback.accept(progress);

                                int currentProgress = (int) progress;
                                int lastLogged = lastLoggedPercent.get();

                                if (currentProgress >= 10 && currentProgress / 10 > lastLogged / 10) {
                                    if (lastLoggedPercent.compareAndSet(lastLogged, currentProgress)) {
                                        log.info("Processing job... {}% complete ({} / {} items).", (currentProgress / 10) * 10, completed, total);
                                    }
                                }

                                if (throwable != null) {
                                    log.error("Error fetching data for an ISBN", throwable);
                                }
                            });
                    })
                    .collect(Collectors.toList());

            List<AladinItemDto> results = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            int rowNum = 1;
            for (AladinItemDto item : results) {
                Row row = sheet.createRow(rowNum++);
                populateRowWithData(row, item);
            }

            // Auto-size columns for better readability
            for (int i = 0; i < getHeader().length; i++) {
                sheet.autoSizeColumn(i);
            }

            return workbook;
        }, taskExecutor);
    }

    private void createHeaderRow(Sheet sheet) {
        Row headerRow = sheet.createRow(0);
        String[] headers = getHeader();
        Workbook workbook = sheet.getWorkbook();
        CellStyle headerStyle = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        headerStyle.setFont(font);

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    private void populateRowWithData(Row row, AladinItemDto item) {
        row.createCell(0).setCellValue(item.getIsbn13());
        row.createCell(1).setCellValue(item.getTitle());

        String title = item.getTitle();
        String mainTitle = "";
        String subTitle = "";
        if (title != null) {
            int dashIndex = title.indexOf(" - ");
            if (dashIndex != -1) {
                mainTitle = title.substring(0, dashIndex).trim();
                subTitle = title.substring(dashIndex + 3).trim();
            } else {
                mainTitle = title.trim();
            }
        }
        row.createCell(2).setCellValue(mainTitle);
        row.createCell(3).setCellValue(subTitle);

        row.createCell(4).setCellValue(item.getAuthor());

        String author = item.getAuthor();
        String primaryAuthor = "";
        if (author != null) {
            int jiEunYiIndex = author.indexOf(" (지은이)");
            if (jiEunYiIndex != -1) {
                primaryAuthor = author.substring(0, jiEunYiIndex).trim();
            } else {
                primaryAuthor = author.trim(); // (지은이) 가 없으면 주제목에 제목 그대로 사용
            }
        }
        row.createCell(5).setCellValue(primaryAuthor);

        row.createCell(6).setCellValue(item.getPublisher());
        row.createCell(7).setCellValue(item.getPubDate());
        row.createCell(8).setCellValue(item.getDescription());
        row.createCell(9).setCellValue(item.getPriceSales());
        row.createCell(10).setCellValue(item.getPriceStandard());
        String coverImageUrl = item.getCover();
        if (coverImageUrl != null) {
            coverImageUrl = coverImageUrl.replace("coversum", "cover500");
        }
        row.createCell(11).setCellValue(coverImageUrl);
        String originalLink = item.getLink();
        String processedLink = originalLink;
        String aladinItemId = "";

        if (originalLink != null && !originalLink.isEmpty()) {
            int itemIdStartIndex = originalLink.indexOf("ItemId=");
            if (itemIdStartIndex != -1) {
                itemIdStartIndex += "ItemId=".length();
                int itemIdEndIndex = originalLink.indexOf("&", itemIdStartIndex);
                if (itemIdEndIndex == -1) {
                    itemIdEndIndex = originalLink.length();
                }
                aladinItemId = originalLink.substring(itemIdStartIndex, itemIdEndIndex);
            }

            int partnerIndex = originalLink.indexOf("&partner=");
            if (partnerIndex != -1) {
                processedLink = originalLink.substring(0, partnerIndex);
            }
        }
        row.createCell(12).setCellValue(processedLink);
        row.createCell(13).setCellValue(aladinItemId);

        if (item.getSubInfo() != null) {
            row.createCell(14).setCellValue(item.getSubInfo().getItemPage());
        }
        row.createCell(15).setCellValue(item.getCategoryName());

        String categoryName = item.getCategoryName();
        if (categoryName != null && !categoryName.isEmpty()) {
            int lastGreaterThanIndex = categoryName.lastIndexOf('>');
            if (lastGreaterThanIndex != -1) {
                String lastPart = categoryName.substring(lastGreaterThanIndex + 1).trim();
                row.createCell(16).setCellValue(lastPart);
            } else {
                row.createCell(16).setCellValue(categoryName.trim());
            }
        } else {
            row.createCell(16).setCellValue("");
        }
    }

    private String[] getHeader() {
        return new String[]{ 
                "ISBN13", "제목", "주제목", "부제목", "저자", "지은이", "출판사", "출판일", "상세설명",
                "판매가", "정가", "표지 이미지 URL", "상품링크", "알라딘 ItemId", "페이지 수",
                "카테고리 체인", "카테고리"
        };
    }
}