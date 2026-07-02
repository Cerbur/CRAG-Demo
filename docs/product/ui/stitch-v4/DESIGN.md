---
name: Systematic Precision
colors:
  surface: '#faf9ff'
  surface-dim: '#d8d9e5'
  surface-bright: '#faf9ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f2f3ff'
  surface-container: '#ecedf9'
  surface-container-high: '#e6e7f3'
  surface-container-highest: '#e0e2ed'
  on-surface: '#181b23'
  on-surface-variant: '#414755'
  inverse-surface: '#2d3039'
  inverse-on-surface: '#eff0fc'
  outline: '#727786'
  outline-variant: '#c1c6d7'
  surface-tint: '#0059c7'
  primary: '#0057c2'
  on-primary: '#ffffff'
  primary-container: '#006ef2'
  on-primary-container: '#fefcff'
  inverse-primary: '#afc6ff'
  secondary: '#5e5e5e'
  on-secondary: '#ffffff'
  secondary-container: '#e1dfdf'
  on-secondary-container: '#636262'
  tertiary: '#9e3d00'
  on-tertiary: '#ffffff'
  tertiary-container: '#c64f00'
  on-tertiary-container: '#fffbff'
  error: '#ff4d4f'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#d9e2ff'
  primary-fixed-dim: '#afc6ff'
  on-primary-fixed: '#001a43'
  on-primary-fixed-variant: '#004398'
  secondary-fixed: '#e4e2e2'
  secondary-fixed-dim: '#c7c6c6'
  on-secondary-fixed: '#1b1c1c'
  on-secondary-fixed-variant: '#464747'
  tertiary-fixed: '#ffdbcc'
  tertiary-fixed-dim: '#ffb695'
  on-tertiary-fixed: '#351000'
  on-tertiary-fixed-variant: '#7c2e00'
  background: '#faf9ff'
  on-background: '#181b23'
  surface-variant: '#e0e2ed'
  success: '#52c41a'
  warning: '#faad14'
  processing: '#1677ff'
  text-primary: '#262626'
  text-disabled: '#8c8c8c'
  bg-container: '#f5f5f5'
  bg-elevated: '#ffffff'
  border-base: '#d9d9d9'
typography:
  headline-lg:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
  headline-md:
    fontFamily: Inter
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
  headline-sm:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '600'
    lineHeight: 24px
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 22px
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 20px
  label-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 22px
  label-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 18px
  mono-md:
    fontFamily: jetbrainsMono
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 20px
rounded:
  sm: 0.125rem
  DEFAULT: 0.25rem
  md: 0.375rem
  lg: 0.5rem
  xl: 0.75rem
  full: 9999px
spacing:
  unit: 4px
  gutter: 16px
  margin-page: 24px
  padding-compact: 8px
  container-max-width: 1200px
---

## Brand & Style

The design system is engineered for the **CRAG Web Console**, a high-density administrative environment focused on knowledge base management and RAG (Retrieval-Augmented Generation) orchestration. The brand personality is **utilitarian, professional, and reliable**, prioritizing information throughput over decorative flair.

The chosen style is **Corporate / Modern**, heavily influenced by Ant Design principles. It utilizes a structured, logic-driven aesthetic characterized by:
- **High Information Density:** Compact spacing and small typography to allow power users to scan large datasets efficiently.
- **Functional Clarity:** A strict color-to-status mapping where visual cues are reserved for state changes and data visualization.
- **Low Visual Noise:** Elimination of gradients, heavy shadows, and hero sections in favor of 1px borders and flat surfaces.
- **Systematic Consistency:** Every element follows a predictable pattern, reducing cognitive load during repetitive management tasks.

## Colors

This design system employs a functional palette where color is used exclusively to communicate intent and status. The default mode is **Light**, optimized for long-duration focused work in office environments.

- **Primary & Processing:** Ant Blue (#1677ff) is the singular anchor for actions, active navigation, and ongoing system processes.
- **Semantic Feedback:** Success (Green), Warning (Gold), and Error (Red) follow industry-standard patterns to ensure immediate recognition of document ingestion and API states.
- **Neutral Hierarchy:**
    - `#f5f5f5` provides a soft backdrop for the application shell.
    - `#ffffff` is reserved for "work surfaces" like cards, table rows, and input fields to maximize contrast.
    - Text follows a strict contrast ratio: Primary (#262626) for content, Secondary (#595959) for descriptions, and Disabled (#8c8c8c) for inactive metadata.

## Typography

The typography system is built on **Inter**, providing a neutral, highly legible foundation for technical data. The base size is set to **14px** to balance readability with high information density requirements.

- **Scale:** A conservative scale is used to prevent layout shifts. Headlines are restrained, as the content structure is primarily driven by grids and borders.
- **Monospace:** JetBrains Mono is used for API Keys, ID strings, and code snippets to ensure character distinction (e.g., 0 vs O).
- **Labels:** Semi-bold weights are utilized for table headers and form labels to provide structural contrast without increasing font size.
- **Mobile Adaptivity:** For small screens, headline sizes are reduced by one tier (e.g., Page Title moves from 24px to 20px) to maximize horizontal space for tabular data.

## Layout & Spacing

The design system uses a **Fixed-Fluid Hybrid Grid**. The sidebar and navigation elements are fixed-width, while the main content area fluidly expands to fill the viewport until reaching a maximum container width of 1200px for optimal readability.

- **Rhythm:** A 4px baseline grid governs all spacing.
- **Layout Model:** A 12-column system is used for dashboard layouts. In the Knowledge Detail view, cards span 4, 6, or 12 columns depending on the complexity of the "Overview" widgets.
- **High Density:** Padding in tables and lists is set to a "Small" (8px) or "Compact" (4px) setting to allow more rows to be visible above the fold.
- **Breakpoints:**
    - **Desktop (1200px+):** Full 200px sidebar, 24px margins.
    - **Tablet (768px - 1199px):** Folded sidebar (icons only), 16px margins.
    - **Mobile (< 768px):** Hidden sidebar (drawer-based), 12px margins, tables reflow into card-list structures.

## Elevation & Depth

To maintain a "flat" professional aesthetic, depth is communicated through **Tonal Layers** and **Low-Contrast Outlines** rather than dramatic shadows.

- **Surface Levels:**
    - Level 0 (App Background): `#f5f5f5`.
    - Level 1 (Cards/Content): `#ffffff` with a 1px border (`#d9d9d9`).
    - Level 2 (Modals/Popovers): `#ffffff` with a subtle ambient shadow (8px blur, 0.08 opacity).
- **Interactive States:** Hovering over a table row or clickable card does not increase elevation; instead, it triggers a background color shift to a light blue tint (`#e6f4ff`) to maintain the 2D plane.
- **Sidebar:** The navigation sidebar uses a dark theme (`#001529`) to create a distinct vertical boundary between navigation and the workspace.

## Shapes

The shape language is **Soft (0.25rem)**, aligned with the precision of a technical tool.

- **Components:** Buttons, Input fields, and Tags use the 4px (Soft) radius to appear modern but grounded.
- **Large Elements:** Cards and Modals use the 8px (Large) radius to subtly distinguish them from the background.
- **Status Tags:** Tags for statuses like "READY" or "FAILED" use a 2px radius or remain slightly squared to preserve a "label" feel rather than a "pill" feel, ensuring they don't look like interactive buttons.

## Components

### Buttons
- **Primary:** Solid blue background, white text. No gradient.
- **Ghost/Default:** 1px border (#d9d9d9), text-primary.
- **Danger:** Solid red background for "Revoke API Key" or "Delete" actions, requiring a Popconfirm secondary step.

### Tables (High Density)
- **Header:** Light gray background (#fafafa), 12px semi-bold text.
- **Cell Height:** Reduced to 40px for "Small" variant to maximize row visibility.
- **Actions:** Icon-only buttons or text links ("Edit", "View") to save horizontal space.

### Status Tags
- **PENDING:** Default gray border/text.
- **PROCESSING:** Blue border/text with a subtle spin icon.
- **READY:** Green border/text.
- **FAILED:** Red border/text.
- *Styling:* Subtle background tint (e.g., 10% opacity of the status color) with a solid 1px border.

### Input Fields
- **Default:** 1px border (#d9d9d9), 14px text.
- **Focus:** Primary blue border with a subtle 2px blue "halo" glow (outline).
- **API Key Display:** Read-only state uses a monospaced font with a "Copy" icon always present.

### Chat Interface
- **Message Bubbles:** User messages have a light blue tint background; assistant messages are plain white with a subtle border.
- **Citations:** Inline superscript numbers [1] linked to a "Sources" footer panel. Sources are styled as compact list items with document name and a snippet of text.

### Feedback & Empty States
- **Empty State:** Centered "In-box" icon, gray secondary text, and a single Primary Action button (e.g., "Upload First Document").
- **Loading:** Global top-bar progress line for page transitions; spinning icons for inline document processing.