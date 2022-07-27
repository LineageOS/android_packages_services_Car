import {json} from '@codemirror/lang-json';
import {StreamLanguage} from '@codemirror/language'
import {lua} from '@codemirror/legacy-modes/mode/lua'
import {EditorView, placeholder} from '@codemirror/view'
import {nord} from 'cm6-theme-nord';
import {basicSetup} from 'codemirror';

/**
 * Waits until the webpage loads and then it calls the
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

  const /** HTMLElement */ publishedDataInput =
      document.getElementById('published-data-input');

  const /** HTMLElement */ savedStateInput =
      document.getElementById('saved-state-input');

  // Adds a custom theme to the CodeMirrors. Makes it so that the CodeMirror
  // takes up the space of its parent container, defines the behavior of the
  // contents if they overflow past the parent container to add a scroll-bar and
  // customizes the scroll-bar appearence.
  const /** Extension */ customTheme = EditorView.theme({
    '&': {
      height: '100%',
      width: 'auto',
    },
    '.cm-scroller': {overflow: 'auto'}
  });

  configureCodeMirror(scriptInput, [
    basicSetup, nord, StreamLanguage.define(lua),
    placeholder(scriptInput.placeholder), customTheme
  ]);

  configureCodeMirror(publishedDataInput, [
    basicSetup, nord, json(), placeholder(publishedDataInput.placeholder),
    customTheme
  ]);

  configureCodeMirror(savedStateInput, [
    basicSetup, nord, json(), placeholder(savedStateInput.placeholder),
    customTheme
  ]);
}

/**
 * Adds a CodeMirror with the given extensions in place of the textArea.
 * Additionally links the input of the CodeMirror with the textArea.
 * @param {HTMLTextAreaElement} textArea The TextArea that is replaced with a
 *     CodeMirror
 * @param {Extension[]} extensions Extensions to add to the configuration of the
 *     CodeMirror.
 */
function configureCodeMirror(textArea, extensions) {
  const /** EditorView */ editor =
      new EditorView({doc: textArea.value, extensions: extensions});

  // Replaces textArea with code editor.
  textArea.parentNode.insertBefore(editor.dom, textArea);

  // The code editor and textArea have no reference of each other,
  // so value must be supplied from the editor when submitting.
  textArea.form.addEventListener(
      'submit', () => {textArea.value = editor.state.doc.toString()});
}
