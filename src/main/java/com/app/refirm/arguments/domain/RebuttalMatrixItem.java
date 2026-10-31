package com.app.refirm.arguments.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Anticipated adversary contention paired with our tactical counter-rebuttal and authority pin.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RebuttalMatrixItem(
        String rebuttalId,
        String anticipatedOppositeArgument,
        String ourCounterRebuttal,
        String statutoryOrEvidentiaryAnchor
) {
    public RebuttalMatrixItem {
        rebuttalId = rebuttalId != null ? rebuttalId : "REB_1";
        anticipatedOppositeArgument = anticipatedOppositeArgument != null ? anticipatedOppositeArgument : "";
        ourCounterRebuttal = ourCounterRebuttal != null ? ourCounterRebuttal : "";
        statutoryOrEvidentiaryAnchor = statutoryOrEvidentiaryAnchor != null ? statutoryOrEvidentiaryAnchor : "";
    }

    public RebuttalMatrixItem withCounterRebuttal(String newCounterRebuttal) {
        return new RebuttalMatrixItem(
                rebuttalId,
                anticipatedOppositeArgument,
                newCounterRebuttal,
                statutoryOrEvidentiaryAnchor
        );
    }
}
