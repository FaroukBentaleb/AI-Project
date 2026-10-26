package com.devassisthub.bug.service;

import com.devassisthub.bug.dto.BugRequest;
import com.devassisthub.bug.dto.BugResponse;
import com.devassisthub.bug.dto.BugStats;
import com.devassisthub.bug.dto.PageResponse;
import com.devassisthub.bug.dto.TriageResponse;
import com.devassisthub.bug.entity.Bug;
import com.devassisthub.bug.entity.BugCategory;
import com.devassisthub.bug.entity.BugStatus;
import com.devassisthub.bug.entity.Severity;
import com.devassisthub.bug.exception.ApiException;
import com.devassisthub.bug.repository.BugRepository;
import com.devassisthub.bug.repository.BugSpecifications;
import com.devassisthub.bug.repository.BugSpecifications.SortOrder;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD of bug reports. */
@Service
public class BugService {

    static final int MAX_PAGE_SIZE = 100;

    private final BugRepository bugs;

    public BugService(BugRepository bugs) {
        this.bugs = bugs;
    }

    @Transactional(readOnly = true)
    public PageResponse<BugResponse> list(BugStatus status, Severity severity, BugCategory category, String q,
                                          SortOrder sort, int page, int size) {
        PageRequest request = PageRequest.of(Math.max(0, page), Math.min(MAX_PAGE_SIZE, Math.max(1, size)));
        return PageResponse.of(bugs.findAll(BugSpecifications.matching(status, severity, category, q,
                sort == null ? SortOrder.NEWEST : sort), request), BugResponse::of);
    }

    @Transactional(readOnly = true)
    public BugResponse get(long id) {
        return BugResponse.of(find(id));
    }

    @Transactional
    public BugResponse create(BugRequest request) {
        Bug bug = new Bug();
        apply(bug, request);
        return BugResponse.of(bugs.save(bug));
    }

    @Transactional
    public BugResponse update(long id, BugRequest request) {
        Bug bug = find(id);
        apply(bug, request);
        return BugResponse.of(bugs.save(bug));
    }

    @Transactional
    public BugResponse changeStatus(long id, BugStatus status) {
        Bug bug = find(id);
        bug.changeStatus(status);
        return BugResponse.of(bugs.save(bug));
    }

    @Transactional
    public void delete(long id) {
        bugs.delete(find(id));
    }

    @Transactional(readOnly = true)
    public BugStats stats() {
        Map<String, Long> byStatus = counts(BugStatus.values(), bugs.countByStatus());
        Map<String, Long> openBySeverity = counts(Severity.values(), bugs.countOpenBySeverity());
        Map<String, Long> byCategory = counts(BugCategory.values(), bugs.countByCategory());
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long open = byStatus.get(BugStatus.OPEN.name()) + byStatus.get(BugStatus.IN_PROGRESS.name());
        return new BugStats(total, open, byStatus, openBySeverity, byCategory);
    }

    /** The stored report as triage input; read in its own short transaction so no transaction spans the model call. */
    @Transactional(readOnly = true)
    public TriageInput triageInput(long id) {
        Bug b = find(id);
        return new TriageInput(b.getTitle(), b.getDescription(), b.getStepsToReproduce(), b.getExpectedResult(),
                b.getActualResult(), b.getStackTrace(), b.getEnvironment(), b.getAppVersion());
    }

    /** Stores a triage suggestion on the bug without touching the values the user chose. */
    @Transactional
    public BugResponse recordTriage(long id, TriageResponse triage) {
        Bug bug = find(id);
        bug.recordTriage(triage.severity(), triage.category(), triage.suggestedTitle(), triage.rationale(),
                triage.model(), triage.promptVersion());
        return BugResponse.of(bugs.save(bug));
    }

    private Bug find(long id) {
        return bugs.findById(id).orElseThrow(() -> ApiException.notFound(id));
    }

    private static void apply(Bug bug, BugRequest r) {
        bug.setTitle(r.title().strip());
        bug.setDescription(r.description().strip());
        bug.setStepsToReproduce(blankToNull(r.stepsToReproduce()));
        bug.setExpectedResult(blankToNull(r.expectedResult()));
        bug.setActualResult(blankToNull(r.actualResult()));
        bug.setStackTrace(blankToNull(r.stackTrace()));
        bug.setEnvironment(blankToNull(r.environment()));
        bug.setAppVersion(blankToNull(r.appVersion()));
        bug.setReporter(blankToNull(r.reporter()));
        bug.setAssignee(blankToNull(r.assignee()));
        bug.setTags(normalizeTags(r.tags()));
        if (r.severity() != null) {
            bug.setSeverity(r.severity());
        }
        if (r.category() != null) {
            bug.setCategory(r.category());
        }
        if (r.status() != null) {
            bug.changeStatus(r.status());
        }
    }

    /** Lower-case, spaces to dashes, duplicates dropped, order kept. */
    static List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag != null && !tag.isBlank()) {
                out.add(tag.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-"));
            }
        }
        return List.copyOf(out);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static <E extends Enum<E>> Map<String, Long> counts(E[] values, List<Object[]> rows) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (E value : values) {
            out.put(value.name(), 0L);
        }
        for (Object[] row : rows) {
            out.put(((Enum<?>) row[0]).name(), ((Number) row[1]).longValue());
        }
        return out;
    }
}
