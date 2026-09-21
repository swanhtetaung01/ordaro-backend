package app.ordaro.shared.numbering;

/**
 * The middle segment of every business number (spec §9 Sequences). Column
 * {@code document_sequence.type}. {@code RCP · RTN · ORD} arrive with sales (step 3).
 */
public enum DocumentSequenceType {
    RCP,
    RTN,
    ORD,
    OPN,
    GRN,
    OUT,
    ADJ,
    TFR
}
