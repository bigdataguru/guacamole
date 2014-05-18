/**
 * Licensed to Big Data Genomics (BDG) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The BDG licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bdgenomics.guacamole.pileup

import org.bdgenomics.adam.rich.DecadentRead
import org.bdgenomics.guacamole.Common

/**
 * A [[Pileup]] at a locus contains a sequence of [[PileupElement]] instances, one for every read that overlaps that
 * locus. Each [[PileupElement]] specifies the base read at the given locus in a particular read. It also keeps track
 * of the read itself and the offset of the base in the read.
 *
 *
 * @param locus The locus on the reference genome
 *
 * @param elements Sequence of [[PileupElement]] instances giving the sequenced bases that align to a particular
 *                 reference locus, in arbitrary order.
 */
case class Pileup(locus: Long, elements: Seq[PileupElement]) {
  /** The first element in the pileup. */
  lazy val head = {
    assume(!elements.isEmpty, "Empty pileup")
    elements.head
  }

  /** The contig name for all elements in this pileup. */
  lazy val referenceName: String = head.read.record.contig.contigName.toString

  assume(elements.forall(_.read.record.contig.contigName.toString == referenceName),
    "Reads in pileup have mismatching reference names")
  assume(elements.forall(_.locus == locus), "Reads in pileup have mismatching loci")

  /** The reference nucleotide base at this pileup's locus. */
  lazy val referenceBase: Char = {
    val mdTag = head.read.record.mdTag.get.getReference(head.read.record)
    mdTag.charAt((head.locus - head.read.record.start).toInt)
  }

  /**
   * Split this [[Pileup]] by sample name. Returns a map from sample name to [[Pileup]] instances that use only reads
   * from that sample.
   */
  lazy val bySample: Map[String, Pileup] = {
    elements.groupBy(element => Option(element.read.record.recordGroupSample).map(_.toString).getOrElse("default")).map({
      case (sample, elements) => (sample, Pileup(locus, elements))
    })
  }

  /**
   * Returns a new [[Pileup]] at a different locus on the same contig.
   *
   * To enable an efficient implementation, the new locus must be greater than the current locus.
   *
   * @param newLocus The locus to move forward to.
   * @param newReads The *new* reads, i.e. those that overlap the new locus, but not the current locus.
   * @return A new [[Pileup]] at the given locus.
   */
  def atGreaterLocus(newLocus: Long, newReads: Iterator[DecadentRead]) = {
    assume(elements.isEmpty || newLocus > locus,
      "New locus (%d) must be greater than current locus (%d)".format(newLocus, locus))
    val reusableElements = elements.filter(element => Common.overlapsLocus(element.read.record, newLocus))
    val updatedElements = reusableElements.map(_.elementAtGreaterLocus(newLocus))
    val newElements = newReads.map(PileupElement(_, newLocus))
    Pileup(newLocus, updatedElements ++ newElements)
  }

}
object Pileup {
  /**
   * Given an iterator over (locus, new reads) pairs, returns an iterator of [[Pileup]] instances at the given loci.
   *
   * @param locusAndReads Iterator of (locus, new reads) pairs, where "new reads" are those that overlap the current
   *                      locus, but not the previous locus.
   *
   * @return An iterator of [[Pileup]] instances at the given loci.
   */
  def pileupsAtLoci(locusAndReads: Iterator[(Long, Iterable[DecadentRead])]): Iterator[Pileup] = {

    val empty: Seq[PileupElement] = Seq()
    val initialEmpty = Pileup(0, empty)

    val iterator = locusAndReads.scanLeft(initialEmpty)((prevPileup: Pileup, pair) => {
      val (locus, newReads) = pair
      prevPileup.atGreaterLocus(locus, newReads.iterator)
    })
    iterator.next() // Discard first element, the initial empty pileup.
    iterator
  }

  /**
   * Given reads and a locus, returns a [[Pileup]] at the specified locus.
   *
   * @param reads Sequence of reads, in any order, that may or may not overlap the locus.
   * @param locus The locus to return a [[Pileup]] at.
   * @return A [[Pileup]] at the given locus.
   */
  def apply(reads: Seq[DecadentRead], locus: Long): Pileup = {
    val elements = reads.filter(read => Common.overlapsLocus(read.record, locus)).map(PileupElement(_, locus))
    val pileup = Pileup(locus, elements)
    assert(pileup.locus == locus, "New pileup has locus %d but exepcted %d".format(pileup.locus, locus))
    pileup
  }
}
