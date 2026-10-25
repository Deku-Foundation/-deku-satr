# Deku Foundation and Deku Satr visual system

Updated: 2026-10-25. This document separates the institutional identity from the product UI. It does not replace an approved Foundation logo or claim that the current app is a final brand release.

## Brand hierarchy
- Organization: Deku Foundation. Use the approved Foundation mark and institutional lockup on organization-level pages, policies, cross-project GitHub surfaces and Foundation announcements.
- Product: Deku Satr. Use the Satr app icon, Arabic product name and Satr interface palette inside the app and product-specific material.
- Do not reuse the Satr shield/icon as the Foundation logo. Until approved Foundation assets are checked into the repository, use a text credit such as “Deku Satr · مشروع من Deku Foundation”; do not invent a new Foundation mark.

## Satr tokens
These are product tokens, not the Foundation identity:
- Deep navy: #112B3A
- Primary teal: #176E66
- Teal highlight: #1B8278
- Light background: #F3F7F7
- Dark background: #0C171D
- Main text: #14242E
- Dark-theme text: #E0E9EA

Use teal for primary actions and active status, neutral surfaces for cards, and error color only for Stop/error states. Reserve the hero gradient for the Home header. Do not apply gradients, heavy borders or decorative badges to every card. Express status with text/icon as well as color.

## Adaptive layout
- Phone portrait: one column, the main action visible from Home, Settings separate, no horizontal scrolling.
- Landscape/split-screen: allow vertical scrolling; respect system insets; keep controls available at narrow widths.
- Foldable/tablet/freeform: center readable content and cap the column at 680 dp until a verified multi-pane design exists.
- RTL and accessibility: no fixed text heights, support Arabic RTL and 1.3x/2.0x font scaling; test TalkBack focus, touch targets, keyboard/mouse, landscape and split-screen.

## UX writing rules
Prefer literal Arabic labels: “بدء التمويه”، “إيقاف الجلسة”، “حساسية اكتشاف الأشخاص”، “توسيع حواف القناع”، “لم تُرصد منطقة شخص بوضوح”، “قد يفوت النموذج بعض الأشخاص”. Avoid vague phrases such as “ذكاء اصطناعي ثوري”، “حماية كاملة”، “دقة 100%”، or “يعمل على كل الأجهزة”. Explain what each action does, what permission it needs, and how to undo it. Do not display a working-looking control for a feature that is not implemented.

## References
- https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes
- https://developer.android.com/develop/ui/compose/accessibility/api-defaults
- https://developer.android.com/develop/ui/compose/accessibility/semantics
- https://developer.android.com/jetpack/androidx/releases/compose-material3
