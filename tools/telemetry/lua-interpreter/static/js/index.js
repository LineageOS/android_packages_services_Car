import {defaultKeymap, history, historyKeymap} from '@codemirror/commands'
import {StreamLanguage} from '@codemirror/language'
import {lua} from '@codemirror/legacy-modes/mode/lua'
import {EditorView, keymap, lineNumbers, placeholder} from '@codemirror/view'
import {nord} from 'cm6-theme-nord';
import {basicSetup} from 'codemirror';

/**
 * This waits until the webpage loads and then it calls the
 * anonymous function, which calls main.
 */
window.onload = () => {
  main();
};

/**
 * Configures the material design elements and the code editor.
 */
function main() {
  document.querySelectorAll('.ripple').forEach((element) => {
    mdc.ripple.MDCRipple.attachTo(element);
  });

  const /** HTMLElement */ scriptInput =
      document.getElementById('script-input');

  const /** EditorView */ editor = new EditorView({
    doc: scriptInput.value,
    extensions: [
      basicSetup, StreamLanguage.define(lua),
      placeholder('Input script here...'), nord,
      keymap.of(defaultKeymap, historyKeymap), lineNumbers(), history()
    ]
  });

  // Replaces textarea with code editor.
  scriptInput.parentNode.insertBefore(editor.dom, scriptInput);

  // The code editor and scriptInput textarea have no reference of each other,
  // so value must be supplied from the editor when submitting.
  scriptInput.form.addEventListener(
      'submit', () => {scriptInput.value = editor.state.doc.toString()});
}