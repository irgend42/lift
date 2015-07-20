package ir.ast

/**
 * Filter pattern.
 * Code for this pattern can be generated.
 *
 * The filter pattern has the following high-level semantics:
 * `Filter()( [x,,1,,, ..., x,,n,,] )( [y,,1,,, ..., y,,m,,] ) = [x<sub>y<sub>1</sub></sub> ..., x<sub>y<sub>m</sub></sub>]`
 *
 * The filter pattern has the following type:
 * `Filter() : [a],,I,, -> [Int],,J,, -> [a],,J,,`
 *
 */
case class Filter() extends FunDecl(arity = 2) with isGenerable

object Filter {
  /**
   * Create an instance of the filter pattern.
   *
   * @param input The input array from which to extract elements.
   * @param ids An array of indices that specify which elements to extract.
   * @return Extracted elements specified by `ids`
   */
  def apply(input: Param, ids: Param): FunCall = {
    Filter()(input, ids)
  }
}
