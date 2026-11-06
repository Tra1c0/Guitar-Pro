package dev.riffworkbench.notation

/**
 * Display scale of the score in the editor and on the practice page, on top of density × zoom (QA 23, Gerätebefund
 * „Partitur größer, am besten nicht zum Zoomen“). Layout units stay as they are (glyph sizes, hit tests, exports); only
 * the screen draws them larger. Zoom 1 (the layout page's 100 %) means this scale.
 *
 * Chosen from the layout of the standard project (Qa23ScoreSizeTest): at 1.25 a fret chip is 25 dp high (fret digits
 * 18.75 dp), strings are 30 dp apart, and a TAB system with its rhythm lane (196 units → 245 dp) still fits the canvas of
 * a 411 × 891 dp phone (≈ 293 dp between the card row and the controls); 1.5 would need 294 dp and scroll vertically.
 */
object ScoreScale {
    const val EDITOR = 1.25f
}
