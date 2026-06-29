package sme.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.request.DiscountRuleRequest;
import sme.backend.dto.response.DiscountCalculationResponse;
import sme.backend.entity.DiscountRule;
import sme.backend.entity.DiscountTier;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.DiscountRuleRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DiscountRuleService {

    private final DiscountRuleRepository repo;

    @Transactional(readOnly = true)
    public List<DiscountRule> getAll() {
        return repo.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public DiscountRule create(DiscountRuleRequest req) {
        DiscountRule rule = DiscountRule.builder()
                .name(req.getName())
                .warehouseId(req.getWarehouseId())
                .isActive(req.getIsActive() != null ? req.getIsActive() : true)
                .maxCashierDiscountPct(req.getMaxCashierDiscountPct() != null
                        ? req.getMaxCashierDiscountPct() : BigDecimal.ZERO)
                .build();
        applyTiers(rule, req);
        return repo.save(rule);
    }

    @Transactional
    public DiscountRule update(UUID id, DiscountRuleRequest req) {
        DiscountRule rule = repo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DiscountRule", id));
        rule.setName(req.getName());
        rule.setWarehouseId(req.getWarehouseId());
        if (req.getIsActive() != null) rule.setIsActive(req.getIsActive());
        if (req.getMaxCashierDiscountPct() != null) rule.setMaxCashierDiscountPct(req.getMaxCashierDiscountPct());
        rule.getTiers().clear();
        applyTiers(rule, req);
        return repo.save(rule);
    }

    @Transactional
    public void delete(UUID id) {
        repo.deleteById(id);
    }

    @Transactional(readOnly = true)
    public DiscountCalculationResponse calculate(BigDecimal totalAmount, UUID warehouseId) {
        Optional<DiscountRule> ruleOpt = repo.findActiveRuleForWarehouse(warehouseId);
        if (ruleOpt.isEmpty()) {
            return DiscountCalculationResponse.builder()
                    .totalAmount(totalAmount)
                    .discountPct(BigDecimal.ZERO)
                    .discountAmount(BigDecimal.ZERO)
                    .build();
        }
        DiscountRule rule = ruleOpt.get();
        List<DiscountTier> sorted = rule.getTiers().stream()
                .sorted(Comparator.comparingLong(DiscountTier::getMinAmount))
                .toList();

        long total = totalAmount.longValue();
        DiscountTier best = null;
        for (DiscountTier tier : sorted) {
            if (total >= tier.getMinAmount()) best = tier;
        }

        DiscountTier next = null;
        if (!sorted.isEmpty()) {
            if (best == null) {
                next = sorted.get(0);
            } else {
                int idx = sorted.indexOf(best);
                if (idx + 1 < sorted.size()) next = sorted.get(idx + 1);
            }
        }

        BigDecimal pct = best != null ? best.getDiscountPct() : BigDecimal.ZERO;
        BigDecimal discAmt = totalAmount.multiply(pct)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);

        return DiscountCalculationResponse.builder()
                .totalAmount(totalAmount)
                .discountPct(pct)
                .discountAmount(discAmt)
                .tierLabel(best != null ? best.getLabel() : null)
                .ruleName(rule.getName())
                .nextTierMinAmount(next != null ? next.getMinAmount() : null)
                .nextTierPct(next != null ? next.getDiscountPct() : null)
                .nextTierLabel(next != null ? next.getLabel() : null)
                .build();
    }

    private void applyTiers(DiscountRule rule, DiscountRuleRequest req) {
        if (req.getTiers() != null) {
            req.getTiers().forEach(t -> rule.getTiers().add(
                    DiscountTier.builder()
                            .minAmount(t.getMinAmount())
                            .discountPct(t.getDiscountPct())
                            .label(t.getLabel())
                            .build()));
        }
    }
}
