package dev.eantillon.expenseledger.service;

import dev.eantillon.expenseledger.domain.CurrencySummary;
import dev.eantillon.expenseledger.domain.Draft;
import dev.eantillon.expenseledger.domain.DraftEditInput;
import dev.eantillon.expenseledger.domain.DraftInput;
import dev.eantillon.expenseledger.domain.EntryType;
import dev.eantillon.expenseledger.domain.LedgerEntry;
import dev.eantillon.expenseledger.domain.LedgerQuery;
import dev.eantillon.expenseledger.domain.ReceivableBalance;
import dev.eantillon.expenseledger.domain.ValidationException;
import dev.eantillon.expenseledger.persistence.LedgerRepository;
import dev.eantillon.expenseledger.persistence.ReportingRepository;
import dev.eantillon.expenseledger.validation.DraftValidator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LedgerService {

    private final LedgerRepository ledger;
    private final ReportingRepository reporting;
    private final DraftValidator validator;

    public LedgerService(
            LedgerRepository ledger,
            ReportingRepository reporting,
            DraftValidator validator) {
        this.ledger = ledger;
        this.reporting = reporting;
        this.validator = validator;
    }

    public ServiceResult createDraft(DraftInput input, String actor) {
        Draft draft = ledger.createDraft(validator.validate(input), actor);
        return new ServiceResult(preview(draft), draftMap(draft));
    }

    public ServiceResult editDraft(
            String draftId, int expectedVersion, DraftEditInput edit, String actor) {
        Draft current = requireDraft(draftId);
        DraftInput input = new DraftInput(
                edit.entryType(),
                edit.amount(),
                edit.currency(),
                edit.occurredOn(),
                edit.merchant(),
                edit.category(),
                edit.person(),
                edit.note(),
                current.rawText(),
                current.sourceChannelId(),
                current.sourceMessageId(),
                edit.relatedEntryId());
        Draft updated = ledger.updateDraft(draftId, expectedVersion, validator.validate(input), actor);
        return new ServiceResult(preview(updated), draftMap(updated));
    }

    public ServiceResult confirmDraft(String draftId, String actor) {
        LedgerEntry entry = ledger.confirmDraft(draftId, actor);
        Map<String, Object> data = entryMap(entry);
        String message = "Recorded " + title(entry.entryType()) + " " + format(
                entry.amountMinor(), entry.currency()) + " on " + entry.occurredOn()
                + ". Entry ID: " + entry.id();
        return new ServiceResult(message, data);
    }

    public ServiceResult cancelDraft(String draftId, String actor) {
        Draft draft = ledger.cancelDraft(draftId, actor);
        return new ServiceResult("Draft " + draft.id() + " is cancelled.", draftMap(draft));
    }

    public ServiceResult listEntries(LedgerQuery query) {
        List<Map<String, Object>> data = ledger.listEntries(query).stream()
                .map(LedgerService::entryMap)
                .toList();
        StringBuilder text = new StringBuilder();
        if (data.isEmpty()) {
            text.append("No matching ledger entries.");
        } else {
            text.append("Matching ledger entries:\n");
            for (Map<String, Object> entry : data) {
                text.append("- ")
                        .append(entry.get("occurred_on")).append(" · ")
                        .append(entry.get("type")).append(" · ")
                        .append(entry.get("amount")).append(" · ")
                        .append(firstNonNull(entry.get("merchant"), entry.get("person"), entry.get("note"), "Unlabeled"))
                        .append(" · ").append(entry.get("id")).append('\n');
            }
        }
        return new ServiceResult(text.toString().stripTrailing(), Map.of("entries", data));
    }

    public ServiceResult listPendingDrafts(int limit) {
        List<Map<String, Object>> data = ledger.listPendingDrafts(limit).stream()
                .map(LedgerService::draftMap)
                .toList();
        return new ServiceResult(
                data.isEmpty() ? "No pending drafts." : data.size() + " pending draft(s).",
                Map.of("drafts", data));
    }

    public ServiceResult summary(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ValidationException("from cannot be after to");
        }
        List<CurrencySummary> totals = ledger.summarize(from, to);
        List<ReceivableBalance> receivables = reporting.openReceivables();
        List<Map<String, Object>> totalData = new ArrayList<>();
        StringBuilder text = new StringBuilder("Ledger summary");
        if (from != null || to != null) {
            text.append(" (")
                    .append(from == null ? "start" : from)
                    .append(" to ")
                    .append(to == null ? "today" : to)
                    .append(')');
        }
        text.append(":\n");
        if (totals.isEmpty()) {
            text.append("- No confirmed movements.\n");
        }
        for (CurrencySummary total : totals) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("currency", total.currency().getCurrencyCode());
            item.put("expenses", format(total.expenseMinor(), total.currency()));
            item.put("refunds", format(total.refundMinor(), total.currency()));
            item.put("net_spent", format(total.netSpentMinor(), total.currency()));
            item.put("loaned", format(total.loanedMinor(), total.currency()));
            item.put("repaid", format(total.repaidMinor(), total.currency()));
            item.put("receivable", format(total.receivableMinor(), total.currency()));
            totalData.add(item);
            text.append("- ").append(total.currency().getCurrencyCode())
                    .append(": net spent ").append(item.get("net_spent"))
                    .append("; receivable ").append(item.get("receivable")).append('\n');
        }

        List<Map<String, Object>> receivableData = receivables.stream()
                .map(balance -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("loan_entry_id", balance.loanEntryId());
                    item.put("person", balance.person());
                    item.put("currency", balance.currency().getCurrencyCode());
                    item.put("original", format(balance.originalMinor(), balance.currency()));
                    item.put("repaid", format(balance.repaidMinor(), balance.currency()));
                    item.put("remaining", format(balance.remainingMinor(), balance.currency()));
                    item.put("occurred_on", balance.occurredOn().toString());
                    return item;
                })
                .toList();
        if (!receivableData.isEmpty()) {
            text.append("Open receivables:\n");
            for (Map<String, Object> item : receivableData) {
                text.append("- ").append(item.get("person")).append(": ")
                        .append(item.get("remaining")).append(" · ")
                        .append(item.get("loan_entry_id")).append('\n');
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", from == null ? null : from.toString());
        data.put("to", to == null ? null : to.toString());
        data.put("totals", totalData);
        data.put("open_receivables", receivableData);
        return new ServiceResult(text.toString().stripTrailing(), data);
    }

    public Draft requireDraft(String id) {
        return ledger.findDraft(id).orElseThrow(() -> new ValidationException("draft was not found"));
    }

    public static Map<String, Object> draftMap(Draft draft) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", draft.id());
        data.put("type", draft.entryType().wireName());
        data.put("amount", format(draft.amountMinor(), draft.currency()));
        data.put("amount_decimal", decimal(draft.amountMinor(), draft.currency()));
        data.put("currency", draft.currency().getCurrencyCode());
        data.put("occurred_on", draft.occurredOn().toString());
        data.put("merchant", draft.merchant());
        data.put("category", draft.category());
        data.put("person", draft.person());
        data.put("note", draft.note());
        data.put("raw_text", draft.rawText());
        data.put("source_channel_id", draft.sourceChannelId());
        data.put("source_message_id", draft.sourceMessageId());
        data.put("related_entry_id", draft.relatedEntryId());
        data.put("status", draft.status().name().toLowerCase());
        data.put("version", draft.version());
        data.put("created_at", draft.createdAt().toString());
        data.put("updated_at", draft.updatedAt().toString());
        return data;
    }

    public static Map<String, Object> entryMap(LedgerEntry entry) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", entry.id());
        data.put("draft_id", entry.draftId());
        data.put("type", entry.entryType().wireName());
        data.put("amount", format(entry.amountMinor(), entry.currency()));
        data.put("amount_decimal", decimal(entry.amountMinor(), entry.currency()));
        data.put("currency", entry.currency().getCurrencyCode());
        data.put("occurred_on", entry.occurredOn().toString());
        data.put("merchant", entry.merchant());
        data.put("category", entry.category());
        data.put("person", entry.person());
        data.put("note", entry.note());
        data.put("raw_text", entry.rawText());
        data.put("source_channel_id", entry.sourceChannelId());
        data.put("source_message_id", entry.sourceMessageId());
        data.put("related_entry_id", entry.relatedEntryId());
        data.put("status", entry.status().name().toLowerCase());
        data.put("created_at", entry.createdAt().toString());
        data.put("updated_at", entry.updatedAt().toString());
        return data;
    }

    private static String preview(Draft draft) {
        StringBuilder preview = new StringBuilder();
        preview.append("Draft preview\n")
                .append("ID: ").append(draft.id()).append('\n')
                .append("Version: ").append(draft.version()).append('\n')
                .append("Type: ").append(title(draft.entryType())).append('\n')
                .append("Amount: ").append(format(draft.amountMinor(), draft.currency())).append('\n')
                .append("Date: ").append(draft.occurredOn()).append('\n');
        append(preview, "Merchant", draft.merchant());
        append(preview, "Category", draft.category());
        append(preview, "Person", draft.person());
        append(preview, "Note", draft.note());
        append(preview, "Related entry", draft.relatedEntryId());
        preview.append("Status: pending\n")
                .append("Confirm, edit, or cancel this draft.");
        return preview.toString();
    }

    private static void append(StringBuilder output, String label, String value) {
        if (value != null) {
            output.append(label).append(": ").append(value).append('\n');
        }
    }

    private static String title(EntryType type) {
        return switch (type) {
            case EXPENSE -> "Expense";
            case REFUND -> "Refund";
            case LOAN -> "Loan";
            case LOAN_PAYMENT -> "Loan payment";
        };
    }

    public static String format(long minorUnits, Currency currency) {
        return currency.getCurrencyCode() + " " + decimal(minorUnits, currency);
    }

    private static String decimal(long minorUnits, Currency currency) {
        return BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits()).toPlainString();
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return "";
    }

    public record ServiceResult(String text, Map<String, Object> data) {
    }
}
