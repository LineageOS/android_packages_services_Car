#!/usr/bin/env python3
"""
Framework to Overlay Style Generator

This script automates the creation of Android theme overlays. It takes a raw
Android framework style block (like `Theme`) and generates an identical
overlay XML file, automatically mapping standard attributes to the public `android:`
namespace and hidden/@hide framework attributes to the private `*android:` namespace.

Usage:
    python generate_overlay.py <input_framework_theme.xml> <output_overlay.xml>

Example:
    python generate_overlay.py framework_theme_input.xml res/values/themes_overlay.xml
"""

import re
import sys

# =============================================================================
# CONFIGURATION: Source of Truth for Private APIs
# =============================================================================

# Attributes that require the *android: prefix
PRIVATE_ATTRS = {
    "colorForegroundInverse",
    "colorBackgroundFloating",
    "primaryContentAlpha",
    "secondaryContentAlpha",
    "textColorPrimaryActivated",
    "textColorPrimaryDisableOnly",
    "textColorPrimaryInverseDisableOnly",
    "textColorPrimaryInverseNoDisable",
    "textColorPrimaryNoDisable",
    "textColorSecondaryActivated",
    "textColorSecondaryNoDisable",
    "textColorSecondaryInverseNoDisable",
    "textColorTertiaryInverse",
    "textColorSearchUrl",
    "textAppearanceLargeInverse",
    "textAppearanceMediumInverse",
    "textAppearanceSmallInverse",
    "textAppearanceEasyCorrectSuggestion",
    "textAppearanceMisspelledSuggestion",
    "textAppearanceAutoCorrectionSuggestion",
    "textAppearanceGrammarErrorSuggestion",
    "editTextColor",
    "editTextBackground",
    "textCheckMarkInverse",
    "buttonCornerRadius",
    "dropdownListPreferredItemHeight",
    "searchResultListItemHeight",
    "activatedBackgroundIndicator",
    "expandableListPreferredItemIndicatorLeft",
    "expandableListPreferredItemIndicatorRight",
    "expandableListPreferredChildIndicatorLeft",
    "expandableListPreferredChildIndicatorRight",
    "findOnPageNextDrawable",
    "findOnPagePreviousDrawable",
    "windowActionBarFullscreenDecorLayout",
    "windowFixedWidthMajor",
    "windowFixedWidthMinor",
    "windowFixedHeightMajor",
    "windowFixedHeightMinor",
    "dialogTitleIconsDecorLayout",
    "dialogCustomTitleDecorLayout",
    "dialogTitleDecorLayout",
    "dialogCornerRadius",
    "alertDialogCenterButtons",
    "toastFrameBackground",
    "panelColorBackground",
    "panelColorForeground",
    "panelMenuIsCompact",
    "panelMenuListWidth",
    "textEditPasteWindowLayout",
    "textEditNoPasteWindowLayout",
    "textEditSidePasteWindowLayout",
    "textEditSideNoPasteWindowLayout",
    "expandableListViewWhiteStyle",
    "gestureOverlayViewStyle",
    "imageWellStyle",
    "errorMessageBackground",
    "errorMessageAboveBackground",
    "keyboardViewStyle",
    "quickContactBadgeOverlay",
    "activityChooserViewStyle",
    "fragmentBreadCrumbsStyle",
    "contextPopupMenuStyle",
    "magnifierStyle",
    "preferenceActivityStyle",
    "seekBarPreferenceStyle",
    "yesNoPreferenceStyle",
    "seekBarDialogPreferenceStyle",
    "preferenceLayoutChild",
    "preferencePanelStyle",
    "preferenceHeaderPanelStyle",
    "preferenceListStyle",
    "preferenceFragmentListStyle",
    "preferenceFragmentPaddingSide",
    "detailsElementBackground",
    "searchWidgetCorpusItemBackground",
    "actionOverflowMenuStyle",
    "actionModeCutDrawable",
    "actionModeCopyDrawable",
    "actionModePasteDrawable",
    "actionModeSelectAllDrawable",
    "actionModeFindDrawable",
    "actionModeUndoDrawable",
    "actionModeRedoDrawable",
    "actionModePopupWindowStyle",
    "segmentedButtonStyle",
    "fingerprintAuthDrawable",
    "floatingToolbarCloseDrawable",
    "floatingToolbarItemBackgroundBorderlessDrawable",
    "floatingToolbarItemBackgroundDrawable",
    "floatingToolbarOpenDrawable",
    "floatingToolbarDividerColor",
    "searchDialogTheme",
    "preferenceFrameLayoutStyle",
    "timePickerDialogTheme",
    "datePickerDialogTheme",
    "fastScrollThumbDrawable",
    "fastScrollTrackDrawable",
    "fastScrollPreviewBackgroundRight",
    "fastScrollPreviewBackgroundLeft",
    "fastScrollOverlayPosition",
    "fastScrollTextColor",
    "colorPressedHighlight",
    "colorLongPressedHighlight",
    "colorFocusedHighlight",
    "colorMultiSelectHighlight",
    "colorActivatedHighlight",
    "colorEdgeEffect",
    "accessibilityFocusedDrawable",
    "autofilledHighlight",
    "lightY",
    "lightZ",
    "lightRadius",
    "ambientShadowAlpha",
    "spotShadowAlpha",
    "tooltipFrameBackground",
    "tooltipForegroundColor",
    "tooltipBackgroundColor",
    "tooltipCornerRadius",
    "tooltipHorizontalPadding",
    "tooltipVerticalPadding",
    "tooltipFontSize",
    "autofillDatasetPickerMaxWidth",
    "autofillDatasetPickerMaxHeight",
    "autofillSaveCustomSubtitleMaxHeight"
}

# Values that require the @*android: or ?*android: prefix
PRIVATE_VALUES = {
    "color/bright_foreground_dark",
    "color/bright_foreground_dark_inverse",
    "dimen/primary_content_alpha_material_dark",
    "dimen/secondary_content_alpha_material_dark",
    "color/red",
    "color/primary_text_dark_disable_only",
    "color/primary_text_light_disable_only",
    "color/primary_text_light_nodisable",
    "color/primary_text_dark_nodisable",
    "color/secondary_text_dark_nodisable",
    "color/secondary_text_light_nodisable",
    "color/hint_foreground_dark",
    "color/hint_foreground_light",
    "color/highlighted_text_dark",
    "color/highlighted_text_light",
    "color/link_text_dark",
    "color/link_text_light",
    "color/search_url_text",
    "string/candidates_style",
    "drawable/indicator_check_mark_dark",
    "drawable/indicator_check_mark_light",
    "style/Widget.CompoundButton.Switch",
    "drawable/item_background",
    "drawable/ic_ab_back_holo_dark",
    "drawable/divider_horizontal_dark",
    "style/Widget.TextView.ListSeparator",
    "drawable/btn_check",
    "drawable/activated_background",
    "drawable/divider_horizontal_bright",
    "attr/expandableListPreferredItemIndicatorLeft",
    "attr/expandableListPreferredItemIndicatorRight",
    "drawable/ic_find_next_holo_dark",
    "drawable/ic_find_previous_holo_dark",
    "drawable/gallery_item_background",
    "drawable/screen_background_selector_dark",
    "style/WindowTitle",
    "style/WindowTitleBackground",
    "color/navigation_bar_default",
    "layout/screen_action_bar",
    "layout/dialog_title_icons",
    "layout/dialog_custom_title",
    "layout/dialog_title",
    "dimen/dialog_padding",
    "style/Theme.Dialog.Alert",
    "style/AlertDialog",
    "style/Theme.DeviceDefault.Dialog.Presentation",
    "drawable/menu_background",
    "drawable/menu_background_fill_parent_width",
    "drawable/scrollbar_handle_horizontal",
    "drawable/scrollbar_handle_vertical",
    "drawable/text_select_handle_left_material",
    "drawable/text_select_handle_right_material",
    "drawable/text_select_handle_middle_material",
    "style/Widget.TextSelectHandle",
    "layout/text_edit_paste_window",
    "layout/text_edit_no_paste_window",
    "layout/text_edit_side_paste_window",
    "layout/text_edit_side_no_paste_window",
    "style/Widget.CheckedTextView",
    "style/Widget.ExpandableListView.White",
    "style/Widget.FastScroll",
    "style/Widget.GestureOverlayView",
    "style/Widget.ImageWell",
    "style/Widget.ListView.White",
    "style/Widget.ProgressBar.Small.Title",
    "style/Widget.ProgressBar.Inverse",
    "style/Widget.ProgressBar.Small.Inverse",
    "style/Widget.ProgressBar.Large.Inverse",
    "style/Widget.RatingBar.Indicator",
    "style/Widget.RatingBar.Small",
    "style/Widget.HorizontalScrollView",
    "drawable/popup_inline_error",
    "drawable/popup_inline_error_above",
    "style/Widget.WebTextView",
    "style/Widget.KeyboardView",
    "drawable/quickcontact_badge_overlay_dark",
    "style/Widget.QuickContactBadge.WindowSmall",
    "style/Widget.QuickContactBadge.WindowMedium",
    "style/Widget.QuickContactBadge.WindowLarge",
    "style/Widget.QuickContactBadgeSmall.WindowSmall",
    "style/Widget.QuickContactBadgeSmall.WindowMedium",
    "style/Widget.QuickContactBadgeSmall.WindowLarge",
    "style/Widget.ActivityChooserView",
    "style/Widget.Magnifier",
    "style/Preference.PreferenceScreen",
    "style/PreferenceActivity",
    "style/PreferenceFragment",
    "style/Preference.Category",
    "style/Preference",
    "style/Preference.Information",
    "style/Preference.CheckBoxPreference",
    "style/Preference.SwitchPreference",
    "style/Preference.SeekBarPreference",
    "style/Preference.DialogPreference.YesNoPreference",
    "style/Preference.DialogPreference",
    "style/Preference.DialogPreference.SeekBarPreference",
    "style/Preference.DialogPreference.EditTextPreference",
    "style/Preference.RingtonePreference",
    "layout/preference_child",
    "style/PreferencePanel",
    "style/PreferenceHeaderPanel",
    "style/PreferenceHeaderList",
    "style/PreferenceFragmentList",
    "dimen/preference_fragment_padding_side",
    "drawable/panel_bg_holo_dark",
    "color/search_widget_corpus_item_background",
    "drawable/cab_background_top_holo_dark",
    "drawable/ic_menu_cut_holo_dark",
    "drawable/ic_menu_copy_holo_dark",
    "drawable/ic_menu_paste_holo_dark",
    "drawable/ic_menu_selectall_holo_dark",
    "drawable/ic_menu_share_holo_dark",
    "drawable/ic_menu_find_holo_dark",
    "drawable/ic_menu_undo_material",
    "drawable/ic_menu_redo_material",
    "style/Widget.ActionMode",
    "dimen/action_bar_default_height",
    "style/TextAppearance.Holo.Widget.ActionBar.Menu",
    "drawable/divider_vertical_dark",
    "style/SegmentedButton",
    "drawable/ic_fingerprint",
    "drawable/ic_ab_back_material_dark",
    "drawable/item_background_borderless_material_dark",
    "drawable/item_background_material_dark",
    "drawable/ic_menu_moreoverflow_material_dark",
    "color/floating_popup_divider_dark",
    "style/Widget.Holo.SearchView",
    "style/Theme.SearchBar",
    "style/Widget.PreferenceFrameLayout",
    "style/Widget.NumberPicker",
    "style/Widget.TimePicker",
    "drawable/scrollbar_handle_accelerated_anim2",
    "drawable/menu_submenu_background",
    "color/legacy_pressed_highlight",
    "color/legacy_long_pressed_highlight",
    "color/legacy_selected_highlight",
    "color/legacy_primary_dark",
    "color/legacy_primary",
    "color/legacy_control_activated",
    "color/legacy_control_normal",
    "color/legacy_button_pressed",
    "color/legacy_button_normal",
    "drawable/view_accessibility_focused",
    "drawable/autofilled_highlight",
    "dimen/light_y",
    "dimen/light_z",
    "dimen/light_radius",
    "dimen/ambient_shadow_alpha",
    "dimen/spot_shadow_alpha",
    "drawable/tooltip_frame",
    "color/bright_foreground_light",
    "color/tooltip_background_light",
    "dimen/tooltip_corner_radius",
    "dimen/tooltip_horizontal_padding",
    "dimen/tooltip_vertical_padding",
    "dimen/tooltip_font_size",
    "dimen/autofill_dataset_picker_max_width",
    "dimen/autofill_dataset_picker_max_height",
    "dimen/autofill_save_custom_subtitle_max_height",
    "style/TextAppearance.EasyCorrectSuggestion",
    "style/TextAppearance.MisspelledSuggestion",
    "style/TextAppearance.AutoCorrectionSuggestion",
    "style/TextAppearance.GrammarErrorSuggestion"
}

# =============================================================================
# LOGIC
# =============================================================================

def process_attribute_name(name):
    # Strip existing namespace if present for clean checking
    clean_name = name.replace("android:", "").replace("*android:", "")

    if clean_name in PRIVATE_ATTRS:
        return f"*android:{clean_name}"
    else:
        return f"android:{clean_name}"

def process_attribute_value(val):
    # Handle hardcoded values (booleans, dimensions, hex colors, etc)
    if not val.startswith("@") and not val.startswith("?"):
        return val

    # Extract symbol (@ or ?) and the core resource path
    match = re.match(r'(@|\?)(?:android:|\*android:)?(.*)', val)
    if not match:
        return val

    symbol, core_path = match.groups()

    # Handle AAPT pseudo-resources that shouldn't be namespaced
    if core_path in ["null", "empty"]:
        return f"{symbol}{core_path}"

    # Check if the reference path is in the private list
    if core_path in PRIVATE_VALUES or (symbol == '?' and f"attr/{core_path}" in PRIVATE_VALUES):
        return f"{symbol}*android:{core_path}"
    else:
        return f"{symbol}android:{core_path}"

def main():
    if len(sys.argv) < 3:
        print("Usage: python generate_overlay.py <input_framework_theme.xml> <output_overlay.xml>")
        return

    input_file = sys.argv[1]
    output_file = sys.argv[2]

    # Regex to capture: [1] Indentation, [2] Attr Name, [3] Attr Value, [4] Trailing Text
    item_regex = re.compile(r'^(\s*)<item name="([^"]+)">([^<]+)</item>(.*)')

    try:
        with open(input_file, 'r') as f_in, open(output_file, 'w') as f_out:
            for line in f_in:
                match = item_regex.match(line)
                if match:
                    indent, name, val, trailing = match.groups()
                    new_name = process_attribute_name(name)
                    new_val = process_attribute_value(val)
                    f_out.write(f'{indent}<item name="{new_name}">{new_val}</item>{trailing}\n')
                else:
                    # Write comments, empty lines, <style>, and <resources> tags exactly as they appear
                    f_out.write(line.rstrip('\n') + '\n')

        print(f"Overlay successfully generated: {output_file}")

    except Exception as e:
        print(f"Error processing files: {e}")

if __name__ == "__main__":
    main()
