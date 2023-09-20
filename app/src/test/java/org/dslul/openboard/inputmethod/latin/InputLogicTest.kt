package org.dslul.openboard.inputmethod.latin

import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.*
import androidx.core.content.edit
import androidx.test.runner.AndroidJUnit4
import org.dslul.openboard.inputmethod.event.Event
import org.dslul.openboard.inputmethod.keyboard.KeyboardSwitcher
import org.dslul.openboard.inputmethod.keyboard.MainKeyboardView
import org.dslul.openboard.inputmethod.latin.common.StringUtils
import org.dslul.openboard.inputmethod.latin.inputlogic.InputLogic
import org.dslul.openboard.inputmethod.latin.settings.Settings
import org.dslul.openboard.inputmethod.latin.utils.DeviceProtectedUtils
import org.dslul.openboard.inputmethod.latin.utils.ScriptUtils
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLog
import java.util.*
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
@Config(shadows = [
    ShadowLocaleManagerCompat::class,
    ShadowInputMethodManager2::class,
    ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class,
    ShadowHandler::class,
])
class InputLogicTest {
    private lateinit var latinIME: LatinIME
    private val settingsValues get() = Settings.getInstance().current
    private val inputLogic get() = latinIME.mInputLogic
    private val connection: RichInputConnection get() = inputLogic.mConnection
    private val composerReader = InputLogic::class.java.getDeclaredField("mWordComposer").apply { isAccessible = true }
    private val composer get() = composerReader.get(inputLogic) as WordComposer
    private val beforeComposingReader = RichInputConnection::class.java.getDeclaredField("mCommittedTextBeforeComposingText").apply { isAccessible = true }
    private val connectionTextBeforeComposingText get() = (beforeComposingReader.get(connection) as CharSequence).toString()
    private val composingReader = RichInputConnection::class.java.getDeclaredField("mComposingText").apply { isAccessible = true }
    private val connectionComposingText get() = (composingReader.get(connection) as CharSequence).toString()

    @Before fun setUp() {
        latinIME = Robolectric.setupService(LatinIME::class.java)
        // start logging only after latinIME is created, avoids showing the stack traces if library is not found
        ShadowLog.setupLogging()
        ShadowLog.stream = System.out
    }

    @Test fun inputCode() {
        reset()
        input('c'.code)
        assertEquals("c", textBeforeCursor)
        assertEquals("c", getText())
        assertEquals("", textAfterCursor)
        assertEquals("c", composingText)
        latinIME.mHandler.onFinishInput()
        assertEquals("", composingText)
    }

    @Test fun delete() {

    }

    @Test fun deleteInWeirdEditor() { // todo: try reproducing the thing requiring to fix incorrect length

    }

    @Test fun insertLetterIntoWord() {
        reset()
        setText("hello")
        setCursorPosition(3) // after first l
        input('i'.code)
        assertEquals("helilo", getWordAtCursor())
        assertEquals("helilo", getText())
        assertEquals(4, getCursorPosition())
        assertEquals(4, cursor)
    }

    @Test fun insertLetterIntoWordWithWeirdEditor() {
        reset()
        currentInputType = 180225 // should not change much, but just to be sure
        setText("hello")
        setCursorPosition(3, weirdTextField = true) // after first l
        input('i'.code)
        assertEquals("helilo", getWordAtCursor())
        assertEquals("helilo", getText())
        assertEquals(4, getCursorPosition())
        assertEquals(4, cursor)
    }

    @Test fun insertLetterIntoOneOfSeveralWords() {
        reset()
        setText("hello my friend")
        setCursorPosition(7) // between m and y
        input('a'.code)
        assertEquals("may", getWordAtCursor())
        assertEquals("hello may friend", getText())
        assertEquals(8, getCursorPosition())
        assertEquals(8, cursor)
    }

    @Test fun insertLetterIntoWordHangul() {
        reset()
        currentScript = ScriptUtils.SCRIPT_HANGUL
        setText("ㅛㅎㄹㅎㅕㅛ")
        setCursorPosition(3)
        input('ㄲ'.code) // fails, as expected from the hangul issue when processing the event in onCodeInput
        assertEquals("ㅛㅎㄹㄲㅎㅕㅛ", getWordAtCursor())
        assertEquals("ㅛㅎㄹㄲㅎㅕㅛ", getText())
        assertEquals("ㅛㅎㄹㄲㅎㅕㅛ", textBeforeCursor + textAfterCursor)
        assertEquals(4, getCursorPosition())
        assertEquals(4, cursor)
    }

    @Test fun setAutospace() {
        println(settingsValues.mAutospaceAfterPunctuationEnabled)
        setAutospaceAfterPunctuation(true)
        println(settingsValues.mAutospaceAfterPunctuationEnabled)
    }

    // ------- helper functions ---------

    // should be called before every test, so the same state is guaranteed
    private fun reset() {
        // reset input connection
        currentScript = ScriptUtils.SCRIPT_LATIN
        text = ""
        selectionStart = 0
        selectionEnd = 0
        composingStart = 0
        composingEnd = 0
        batchEdit = 0

        // reset settings
        DeviceProtectedUtils.getSharedPreferences(latinIME).edit { clear() }

        currentInputType = InputType.TYPE_CLASS_TEXT

        // todo: does setText("") work?
        //  plus restarting = true maybe?
        //  that may be the better method for setting a new text field
        connection.setSelection(0, 0) // resets cache
        inputLogic.restartSuggestionsOnWordTouchedByCursor(settingsValues, currentScript)
    }

    private fun input(codePoint: Int) {
        val oldBefore = textBeforeCursor
        val oldAfter = textAfterCursor
        val insert = StringUtils.newSingleCodePointString(codePoint)

        latinIME.onEvent(Event.createEventForCodePointFromUnknownSource(codePoint))
        handleMessages()

        assertEquals(oldBefore + insert, textBeforeCursor)
        assertEquals(oldAfter, textAfterCursor)
        assertEquals(textBeforeCursor + textAfterCursor, getText())
        checkConnectionConsistency()
    }

    // almost the same as codePoint input, but calls different latinIME function
    private fun input(insert: String) {
        val oldBefore = textBeforeCursor
        val oldAfter = textAfterCursor

        latinIME.onTextInput(text)
        handleMessages()

        assertEquals(oldBefore + insert, textBeforeCursor)
        assertEquals(oldAfter, textAfterCursor)
        assertEquals(textBeforeCursor + textAfterCursor, getText())
        checkConnectionConsistency()
    }

    private fun getWordAtCursor() = connection.getWordRangeAtCursor(
        settingsValues.mSpacingAndPunctuations,
        currentScript,
        false
    ).mWord

    private fun getUnderlinedWord(): String {
        val word = getText().substring(inputLogic.composingStart, inputLogic.composingStart + inputLogic.composingLength)
        assertEquals(word, composingText)
        assertEquals(word, connectionComposingText) // no, this will fail as it returns only text until the cursor
        return word
    }

    private fun setCursorPosition(start: Int, end: Int = start, weirdTextField: Boolean = false) {
        val ei = EditorInfo()
        ei.inputType = currentInputType
        ei.initialSelStart = start
        ei.initialSelEnd = end
        // imeOptions should not matter

        // adjust text in inputConnection first, otherwise fixLyingCursorPosition will move cursor
        // to the end of the text
        val fullText = textBeforeCursor + textAfterCursor
        assertEquals(fullText, getText())

        // need to update ic before, otherwise when reloading text cache from ic, ric will load wrong text before cursor
        val oldStart = selectionStart
        val oldEnd = selectionEnd
        selectionStart = start
        selectionEnd = end
        assertEquals(fullText, textBeforeCursor + textAfterCursor)

        latinIME.onUpdateSelection(oldStart, oldEnd, start, end, composingStart, composingEnd)
        handleMessages()

        if (weirdTextField) {
            // todo: when to handle messages from update selection?
            latinIME.mHandler.onStartInput(ei, true) // essentially does nothing
            latinIME.mHandler.onStartInputView(ei, true) // does the thing
            handleMessages()
        }

        assertEquals(fullText, getText()) // this may only be correct after start input?
        assertEquals(start, selectionStart)
        assertEquals(end, selectionEnd)
        checkConnectionConsistency()
    }

    // assumes we have nothing selected
    private fun getCursorPosition(): Int {
        assertEquals(cursor, connection.expectedSelectionStart)
        assertEquals(cursor, connection.expectedSelectionEnd)
        return cursor
    }

    // just sets the text and starts input so connection it set up correctly
    // todo: update selection to automatically set composing span?
    //  here it's -1, -1 in the end, but it probably shouldn't be (and isn't in tests)
    private fun setText(newText: String) {
        text = newText
        selectionStart = newText.length
        selectionEnd = selectionStart
        composingStart = -1
        composingStart = -1

        // we need to start input to notify that something changed
        // restarting is false, so this is seen as a new text field
        val ei = EditorInfo()
        ei.inputType = currentInputType
        latinIME.mHandler.onStartInput(ei, false)
        latinIME.mHandler.onStartInputView(ei, false)
        handleMessages() // this is important so the composing span is set correctly
        checkConnectionConsistency()
    }

    private fun checkConnectionConsistency() {
        println("consistency: $selectionStart, ${connection.expectedSelectionStart}, $selectionEnd, ${connection.expectedSelectionEnd}, $textBeforeComposingText, " +
                "$connectionTextBeforeComposingText, $composingText, $connectionComposingText, $textBeforeCursor, ${connection.getTextBeforeCursor(textBeforeCursor.length, 0)}," +
                " $textAfterCursor, ${connection.getTextBeforeCursor(textAfterCursor.length, 0)}")
        assertEquals(selectionStart, connection.expectedSelectionStart)
        assertEquals(selectionEnd, connection.expectedSelectionEnd)
        assertEquals(textBeforeComposingText, connectionTextBeforeComposingText)
        assertEquals(composingText, connectionComposingText)
        assertEquals(textBeforeCursor, connection.getTextBeforeCursor(textBeforeCursor.length, 0).toString())
        assertEquals(textAfterCursor, connection.getTextAfterCursor(textAfterCursor.length, 0).toString())
    }

    private fun getText() =
        connection.getTextBeforeCursor(100, 0).toString() + connection.getTextAfterCursor(100, 0)

    // nice, this really reloads the prefs!!
    private fun setAutospaceAfterPunctuation(enabled: Boolean) {
        DeviceProtectedUtils.getSharedPreferences(latinIME)
            .edit { putBoolean(Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, enabled) }
        assertEquals(enabled, settingsValues.mAutospaceAfterPunctuationEnabled)
    }

    // always need to handle messages for proper simulation
    private fun handleMessages() {
        while (messages.isNotEmpty()) {
            latinIME.mHandler.handleMessage(messages.first())
            messages.removeFirst()
        }
        while (delayedMessages.isNotEmpty()) {
            latinIME.mHandler.handleMessage(delayedMessages.first())
            delayedMessages.removeFirst()
            // delayed messages may post further messages, handle before next delayed message
            while (messages.isNotEmpty()) {
                latinIME.mHandler.handleMessage(messages.first())
                messages.removeFirst()
            }
        }
        assertEquals(0, messages.size)
        assertEquals(0, delayedMessages.size)
    }

}

private var currentInputType = InputType.TYPE_CLASS_TEXT
private var currentScript = ScriptUtils.SCRIPT_LATIN
private val messages = mutableListOf<Message>() // for latinIME / ShadowInputMethodService
private val delayedMessages = mutableListOf<Message>() // for latinIME / ShadowInputMethodService
// inputconnection stuff
private var batchEdit = 0
private var text = ""
private var selectionStart = 0
private var selectionEnd = 0
private var composingStart = -1
private var composingEnd = -1
// convenience for access
private val textBeforeCursor get() = text.substring(0, selectionStart)
private val textAfterCursor get() = text.substring(selectionEnd)
private val cursor get() = if (selectionStart == selectionEnd) selectionStart else -1

// todo: maybe this is not correct? seems to return only up to the cursor
//private val textBeforeComposingText get() = if (composingStart == -1) text else text.substring(0, composingStart)
private val textBeforeComposingText get() = if (composingStart == -1) textBeforeCursor else text.substring(0, composingStart)

// todo: maybe this is not correct? seems to return only up to the cursor
//private val composingText get() = if (composingStart == -1 || composingEnd == -1) ""
//    else text.substring(composingStart, composingEnd)
private val composingText get() = if (composingStart == -1 || composingEnd == -1) ""
    else text.substring(composingStart, min(composingEnd, selectionEnd)) // will crash if composing start is after cursor, but maybe it should?

// essentially this is the text field we're editing in
private val ic = object : InputConnection {
    // pretty clear (though this may be slow depending on the editor)
    // bad return value here is likely the cause for that weird bug improved/fixed by fixIncorrectLength
    override fun getTextBeforeCursor(p0: Int, p1: Int): CharSequence  = textBeforeCursor.take(p0)
    // pretty clear (though this may be slow depending on the editor)
    override fun getTextAfterCursor(p0: Int, p1: Int): CharSequence = textAfterCursor.take(p0)
    // pretty clear
    override fun getSelectedText(p0: Int): CharSequence? = if (selectionStart == selectionEnd) null
        else text.substring(selectionStart, selectionEnd)
    // inserts text at cursor (right?), and sets it as composing text
    // this REPLACES currently composing text (even if at a different position)
    // moves the cursor: positive means relative to composing text, negative means relative to start
    override fun setComposingText(newText: CharSequence, cursor: Int): Boolean {
        // first remove the composing text if necessary
        if (composingStart != -1 && composingEnd != -1)
            text = textBeforeComposingText + text.substring(composingEnd)
        else // no composing span active, we should remove selected text
            if (selectionStart != selectionEnd) {
                text = textBeforeCursor + textAfterCursor
                selectionEnd = selectionStart
            }
        // then set the new text at old composing start
        // if no composing start, set it at cursor position
        val insertStart = if (composingStart == -1) selectionStart else composingStart
        text = text.substring(0, insertStart) + newText + text.substring(insertStart)
        composingStart = insertStart
        composingEnd = insertStart + newText.length
        selectionStart = if (insertStart > 0) insertStart + cursor
        else cursor
        selectionEnd = selectionStart
        // todo: this should call InputMethodManager#updateSelection(View, int, int, int, int)
        //  but only after batch edit has ended
        //  this is not used in RichInputMethodManager, but probably ends up in LatinIME.onUpdateSelection
        //  -> DO IT (though it will likely only trigger that belatedSelectionUpdate thing, it might be relevant)
        return true
    }
    override fun setComposingRegion(p0: Int, p1: Int): Boolean {
        composingStart = p0
        composingEnd = p1
        return true // never checked
    }
    // sets composing text empty, but doesn't change actual text
    override fun finishComposingText(): Boolean {
        composingStart = -1
        composingEnd = -1
        return true // always true
    }
    // as per documentation: "This behaves like calling setComposingText(text, newCursorPosition) then finishComposingText()"
    override fun commitText(p0: CharSequence, p1: Int): Boolean {
        setComposingText(p0, p1)
        finishComposingText()
        return true // whether we added the text
    }
    // just tells the text field that we add many updated, and that the editor should not
    // send status updates until batch edit ended (not actually used for this simulation)
    override fun beginBatchEdit(): Boolean {
        ++batchEdit
        return true // always true
    }
    // end a batch edit, but maybe there are multiple batch edits happening
    override fun endBatchEdit(): Boolean {
        if (batchEdit > 0)
            return --batchEdit == 0
        return false // returns true if there is still a batch edit ongoing
    }
    // should notify about cursor info containing composing text, selection, ...
    // todo: maybe that could be interesting, implement it?
    override fun requestCursorUpdates(p0: Int): Boolean {
        // we call this, but don't have onUpdateCursorAnchorInfo overridden in latinIME, so it does nothing
        // also currently we don't care about the return value
        return false
    }
    override fun setSelection(p0: Int, p1: Int): Boolean {
        selectionStart = p0
        selectionEnd = p1
        // todo: call InputMethodService.onUpdateSelection(int, int, int, int, int, int), but only after batch edit is done!
        return true
    }
    override fun getCursorCapsMode(p0: Int): Int = TODO("Not yet implemented")
    override fun getExtractedText(p0: ExtractedTextRequest?, p1: Int): ExtractedText = TODO("Not yet implemented")
    override fun deleteSurroundingText(p0: Int, p1: Int): Boolean = TODO("Not yet implemented")
    override fun deleteSurroundingTextInCodePoints(p0: Int, p1: Int): Boolean = TODO("Not yet implemented")
    override fun commitCompletion(p0: CompletionInfo?): Boolean = TODO("Not yet implemented")
    override fun commitCorrection(p0: CorrectionInfo?): Boolean = TODO("Not yet implemented")
    override fun performEditorAction(p0: Int): Boolean = TODO("Not yet implemented")
    override fun performContextMenuAction(p0: Int): Boolean = TODO("Not yet implemented")
    override fun sendKeyEvent(p0: KeyEvent?): Boolean = TODO("Not yet implemented")
    override fun clearMetaKeyStates(p0: Int): Boolean = TODO("Not yet implemented")
    override fun reportFullscreenMode(p0: Boolean): Boolean = TODO("Not yet implemented")
    override fun performPrivateCommand(p0: String?, p1: Bundle?): Boolean = TODO("Not yet implemented")
    override fun getHandler(): Handler? = TODO("Not yet implemented")
    override fun closeConnection() = TODO("Not yet implemented")
    override fun commitContent(p0: InputContentInfo, p1: Int, p2: Bundle?): Boolean = TODO("Not yet implemented")
}

// Shadows are handled by Robolectric. @Implementation overrides built-in functionality.
// This is used for avoiding crashes (LocaleManagerCompat, InputMethodManager, KeyboardSwitcher)
// and for simulating system stuff (InputMethodService for controlling the InputConnection, which
// more or less is the contents of the text field), and for setting the current script in
// KeyboardSwitcher without having to care about InputMethodSubtypes

// could also extend LatinIME, it's not final anyway
@Implements(InputMethodService::class)
class ShadowInputMethodService {
    @Implementation
    fun getCurrentInputEditorInfo() = EditorInfo().apply {
        inputType = currentInputType
        // anything else?
    }
    @Implementation
    fun getCurrentInputConnection() = ic
    @Implementation
    fun isInputViewShown() = true // otherwise selection updates will do nothing
}

@Implements(Handler::class)
class ShadowHandler {
    @Implementation
    fun sendMessage(message: Message) {
        messages.add(message)
    }
    @Implementation
    fun sendMessageDelayed(message: Message, delay: Long) {
        delayedMessages.add(message)
    }
}

@Implements(KeyboardSwitcher::class)
class ShadowKeyboardSwitcher {
    @Implementation
    // basically only needed for null check
    fun getMainKeyboardView(): MainKeyboardView = Mockito.mock(MainKeyboardView::class.java)
    @Implementation
    // only affects view
    fun setKeyboard(keyboardId: Int, toggleState: KeyboardSwitcher.KeyboardSwitchState) = Unit
    @Implementation
    // only affects view
    fun setOneHandedModeEnabled(enabled: Boolean) = Unit
    @Implementation
    // only affects view
    fun getCurrentKeyboardScriptId() = currentScript
}