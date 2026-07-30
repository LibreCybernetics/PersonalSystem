package noesis.logic

/** OWL 2 profile checking (SPEC §3.1: the EL profile is preferred and the system warns when an
  * axiom leaves it, because EL keeps classification polynomial).
  *
  * A warning, never a rejection — the spec sets DL as the ceiling, not EL.
  *
  * Profile membership is defined by OWL 2 Profiles §4 as a purely *syntactic* property of an axiom:
  * it inspects no graph, no closure and no justification. So it belongs beside the axiom algebra
  * rather than in the reasoner, which is also what lets a profile-checker conformance suite run
  * against this module alone.
  */
object Profile:
  /** Why this axiom leaves OWL 2 EL, if it does. */
  def elWarning(axiom: Axiom): Option[String] = axiom match
    case Axiom.InverseProperties(a, b) =>
      Some(s"inverse properties (${a.local}/${b.local}) are outside OWL 2 EL")
    case Axiom.SymmetricProperty(p) =>
      Some(s"symmetric property ${p.local} is outside OWL 2 EL (it requires inverses)")
    case Axiom.PropertyChain(steps, sup) if steps.exists(_.inverse) =>
      Some(s"the chain defining ${sup.local} uses an inverse step, which is outside OWL 2 EL")
    case Axiom.DifferentIndividuals(a, b) =>
      Some(s"asserting ${a.local} ≠ ${b.local} is outside OWL 2 EL")
    case _ => None

  def isEl(axiom: Axiom): Boolean = elWarning(axiom).isEmpty

  def warnings(axioms: IterableOnce[Axiom]): List[(Axiom, String)] =
    axioms.iterator.flatMap(a => elWarning(a).map(a -> _)).toList
