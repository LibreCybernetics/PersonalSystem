package dev.librecybernetics.noesis.app

import dev.librecybernetics.noesis.logic.*
import dev.librecybernetics.noesis.reasoner.Closure

/** Shared structured-assertion typing for every local owner surface (SPEC §3.5.3). */
object Assertions:
  def build(closure: Closure, subject: Iri, property: Iri, value: String): Axiom =
    val candidate = Workspace.iri(value)
    val view = closure.view

    def objectAssertion = Axiom.ObjectAssertion(subject, property, candidate)
    def dataAssertion = Axiom.DataAssertion(subject, property, Literal.parse(value))

    if property == Vocab.rdfType then Axiom.ClassAssertion(subject, candidate)
    else if view.ranges.contains(property) then objectAssertion
    else if property == Vocab.label then dataAssertion
    else if view.objectByProperty.contains(property) then objectAssertion
    else if view.dataByProperty.contains(property) then dataAssertion
    else if value.contains(':') then objectAssertion
    else dataAssertion

