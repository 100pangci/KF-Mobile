package work.miaola.kf

import kotlin.test.*

class EditorBehaviorTest {
    @Test fun emoticonInsertsAtCursorNotAtEnd() {
        val inserted = insertEditorText("前文后文", 2, 2, "[s:57]")
        assertEquals("前文[s:57]后文", inserted.text)
        assertEquals(8, inserted.cursor)
    }
    @Test fun emoticonReplacesSelection() {
        assertEquals("甲[s:41]丁", insertEditorText("甲乙丙丁", 3, 1, "[s:41]").text)
    }
    @Test fun formattingPreservesSelectedText() {
        assertEquals("甲[b]乙丙[/b]丁", wrapEditorSelection("甲乙丙丁", 1, 3, "[b]", "[/b]").text)
        assertEquals(4, wrapEditorSelection("甲乙", 1, 1, "[b]", "[/b]").cursor)
    }
    @Test fun expressionSizeUsesDpAndReaderFontScale() {
        val source = "https://kf.miaola.work/123/post/smile/em/em48.gif"
        assertEquals(InlineImageSize(108, 108), inlineImageSize(source, 20, 20, 900, 3f, 1f))
        assertEquals(InlineImageSize(162, 81), inlineImageSize(source, 40, 20, 900, 3f, 1.5f))
    }
    @Test fun articleImagesKeepAspectRatioAndAreNotUpscaledLikeEmoji() {
        assertEquals(InlineImageSize(800, 400), inlineImageSize("https://example.com/photo.jpg", 1600, 800, 800, 3f, 1f))
        assertEquals(InlineImageSize(40, 20), inlineImageSize("https://example.com/icon.png", 40, 20, 800, 3f, 1f))
    }
}
