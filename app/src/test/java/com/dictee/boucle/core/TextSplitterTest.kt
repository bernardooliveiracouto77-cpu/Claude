package com.dictee.boucle.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSplitterTest {

    private fun sentencesOf(text: String): List<String> =
        TextSplitter.paragraphs(text).flatten().map { text.substring(it.start, it.end) }

    private fun partsOf(text: String, size: PartSize = PartSize.TWO_THREE): List<String> =
        TextSplitter.split(text, size).map { text.substring(it.start, it.end) }

    @Test
    fun simpleSentences() {
        assertEquals(
            listOf("Le chat dort.", "Le chien aboie !", "Où est la souris ?"),
            sentencesOf("Le chat dort. Le chien aboie ! Où est la souris ?")
        )
    }

    @Test
    fun abbreviationsAreNotSentenceEnds() {
        val text = "M. Dupont et Mme. Durand voient le Dr. Martin. Voir p. 12 et cf. chap. 3. Fin."
        assertEquals(
            listOf("M. Dupont et Mme. Durand voient le Dr. Martin.", "Voir p. 12 et cf. chap. 3.", "Fin."),
            sentencesOf(text)
        )
    }

    @Test
    fun moreAbbreviations() {
        val text = "Prenez par ex. Une pomme. Le n° 5 gagne. J. Dupont arrive."
        assertEquals(
            listOf("Prenez par ex. Une pomme.", "Le n° 5 gagne.", "J. Dupont arrive."),
            sentencesOf(text)
        )
    }

    @Test
    fun etcEndsSentenceBeforeCapital() {
        assertEquals(
            listOf("Des pommes, des poires, etc.", "Ensuite on mange."),
            sentencesOf("Des pommes, des poires, etc. Ensuite on mange.")
        )
    }

    @Test
    fun singleLineBreakEndsSentence() {
        val poem = "Le vent se lève\nil faut tenter de vivre\nL'air immense ouvre et referme mon livre"
        assertEquals(3, sentencesOf(poem).size)
        assertEquals(listOf(3), TextSplitter.paragraphs(poem).map { it.size })
    }

    @Test
    fun balancedGroups() {
        assertEquals(listOf(2, 2), TextSplitter.groupSizes(4, 3))
        assertEquals(listOf(3, 2), TextSplitter.groupSizes(5, 3))
        assertEquals(listOf(3, 2, 2), TextSplitter.groupSizes(7, 3))
        assertEquals(listOf(3, 3), TextSplitter.groupSizes(6, 3))
        assertEquals(listOf(1), TextSplitter.groupSizes(1, 3))
        assertEquals(listOf(2), TextSplitter.groupSizes(2, 3))
        assertEquals(listOf(1, 1, 1), TextSplitter.groupSizes(3, 1))
        assertEquals(listOf(2, 2, 1), TextSplitter.groupSizes(5, 2))
        assertEquals(listOf(4, 3), TextSplitter.groupSizes(7, 4))
    }

    @Test
    fun partsNeverMixParagraphs() {
        val text = "Titre du texte\n\nUn. Deux. Trois. Quatre.\n\nCinq. Six. Sept. Huit. Neuf.\n\nSeule phrase."
        assertEquals(
            listOf(
                "Titre du texte",
                "Un. Deux.", "Trois. Quatre.",
                "Cinq. Six. Sept.", "Huit. Neuf.",
                "Seule phrase."
            ),
            partsOf(text)
        )
    }

    @Test
    fun sevenSentencesGiveThreeTwoTwo() {
        val text = "A un. B deux. C trois. D quatre. E cinq. F six. G sept."
        val parts = TextSplitter.split(text, PartSize.TWO_THREE)
        assertEquals(listOf(3, 2, 2), parts.map { it.sentences.size })
    }

    @Test
    fun partSizeSettings() {
        val text = "A un. B deux. C trois. D quatre. E cinq."
        assertEquals(listOf(1, 1, 1, 1, 1), TextSplitter.split(text, PartSize.ONE).map { it.sentences.size })
        assertEquals(listOf(2, 2, 1), TextSplitter.split(text, PartSize.TWO).map { it.sentences.size })
        assertEquals(listOf(3, 2), TextSplitter.split(text, PartSize.TWO_THREE).map { it.sentences.size })
        assertEquals(listOf(3, 2), TextSplitter.split(text, PartSize.THREE_FOUR).map { it.sentences.size })
    }

    @Test
    fun blankLinesWithSpacesAndCrlf() {
        val text = "Premier paragraphe. Encore.\r\n \r\n \nDeuxième paragraphe."
        assertEquals(listOf("Premier paragraphe. Encore.", "Deuxième paragraphe."), partsOf(text))
    }

    @Test
    fun rangesPointIntoOriginalText() {
        val text = "  Bonjour à tous.   Comment allez-vous ?  \n\n  Bien.  "
        val parts = TextSplitter.split(text, PartSize.TWO_THREE)
        assertEquals(2, parts.size)
        assertEquals("Bonjour à tous.   Comment allez-vous ?", text.substring(parts[0].start, parts[0].end))
        assertEquals("Bien.", text.substring(parts[1].start, parts[1].end))
        for (p in parts) for (s in p.sentences) assertTrue(s.start >= p.start && s.end <= p.end)
    }

    @Test
    fun emptyText() {
        assertTrue(TextSplitter.split("", PartSize.TWO_THREE).isEmpty())
        assertTrue(TextSplitter.split("\n\n   \n", PartSize.TWO_THREE).isEmpty())
    }

    @Test
    fun speechChunksAtCommas() {
        assertEquals(
            listOf("Le matin,", "dès l'aube,", "je partirai ;", "note :", "fin."),
            TextSplitter.speechChunks("Le matin, dès l'aube, je partirai ; note : fin.", true)
        )
        assertEquals(
            listOf("Le matin, dès l'aube, je partirai."),
            TextSplitter.speechChunks("Le matin,\n dès l'aube, je partirai.", false)
        )
        assertEquals(listOf("Il coûte 3,5 euros."), TextSplitter.speechChunks("Il coûte 3,5 euros.", true))
    }
}
