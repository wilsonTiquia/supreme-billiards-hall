# Shared controls

`Button` is an action; `ButtonLink` is navigation (React Router `to`, `state`, target and other link props). Both accept `primary`, `secondary`, `tertiary`, and `danger`. Never wrap a button in a link. Keep text labels; icon-only buttons need `aria-label`. Existing button submit semantics are unchanged: specify `type="button"` inside forms for non-submit actions.

`Icon` takes a typed `name`. All glyphs are decorative and inherit text color. Label the containing control or put readable status text beside a status icon. `ThemeIcon` reuses the same sun/moon glyphs.

```tsx
<ButtonLink variant="tertiary" to="/floor"><Icon name="back" />Back to floor</ButtonLink>
<ActionMenu label={`Actions for ${table.name}`} items={[
  { id: 'edit', label: 'Edit', onSelect: () => setEditing(table) },
  { id: 'archive', label: 'Archive', danger: true, disabled: cannotArchive,
    hidden: !canManage, onSelect: () => setArchiveConfirmation(table) },
]} />
```

`ActionMenu` uses the native popover top layer (as the existing report export control does), so overflow containers cannot clip it. Pass the existing permission and pending restrictions as `hidden`/`disabled`; an entirely hidden list disables the trigger. A disabled item remains discoverable to keyboard/screen-reader users but cannot run or navigate. Navigation items use `to` and optional `state`; action items use `onSelect`. The menu closes and returns focus before calling an action, allowing the existing confirmation dialog to take focus. Do not put confirmation or API permission rules in the menu itself.

Enter/Space or ArrowDown opens at the first item; ArrowUp opens at the last. Arrow keys wrap, Home/End jump, typing a letter finds a matching item. Escape returns to the trigger. Tab/Shift+Tab dismiss and continue past/before the trigger. Clicking outside dismisses without stealing focus from another control. Long menus scroll and stay within the viewport.

`Pagination` takes a **one-based display** `page`, `pages`, count text, `pending`, and `onPrevious`/`onNext`. The caller owns fetching, filtering, sorting, URL state and indexing. Reports retains its one-based client pages; Audit/Sales retain zero-based API pages and pass `data.page + 1`. Both controls disable at boundaries or while fetching. Initial loading/error handling stays in the owning screen. Pagination is hidden when printing. Reports keeps its filtered row count and PHP note.

The E2E fixture at `frontend/e2e/controls/` exercises new primitives without adopting feature actions from later PRs. It is a Vite development-only HTML entry and is not part of the production build.
