package ir.ast

/**
 * asScalar pattern. (a.k.a., joinVec).
 * Code for this pattern can be generated.
 *
 * The asScalar pattern has the following high-level semantics:
 *   `asScalar()( [ <x,,1,,, ..., x,,n,,>, ..., <x,,m-n+1,,, ..., x,,m,,> ] ) =
 *    [x,,1,,, ..., x,,m,,]`
 *
 * The asScalar pattern has the following type:
 *   `asScalar(): [ < a >,,i,, ],,j,, -> [ a ],,i x j,,`
 *
 * We know the following algorithmic rewrite rules for the asScalar pattern
 * (so far):
 *  - `asScalar() o asVector(n) | asVector(n) o asScalar() => id`
 */
case class asScalar() extends Pattern(arity = 1) with isGenerable