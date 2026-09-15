"use client";

/** Edit-mode side panel: add toolbar, properties form, save/cancel/delete, conflict and error notices. */
import type { Dispatch } from "react";
import type { Feature, FeatureType } from "../lib/api/types";
import { BUILDING_CATEGORIES, FEATURE_TYPES, ROAD_CLASSES } from "../lib/api/types";
import { draftToInput, isDirty, type EditorAction, type EditorState } from "../lib/editor/draft";
import { railwaysAtVertex } from "../lib/editor/geometry";
import { detailsByField, MAX_DESCRIPTION_LENGTH, MAX_NAME_LENGTH, validateInput } from "../lib/editor/validate";
import { formatTimestamp } from "../lib/features/layers";
import { CATEGORY_LABELS, displayName, ROAD_CLASS_LABELS, TYPE_LABELS } from "../lib/features/styles";
import styles from "./ViewerApp.module.css";

export interface EditorPanelProps {
  state: EditorState;
  dispatch: Dispatch<EditorAction>;
  allFeatures: readonly Feature[];
  /** Transient hint from map interaction (e.g. station not on a railway point). */
  mapHint: string | null;
  /** Unsaved-changes prompt is open. */
  discardPrompt: boolean;
  onConfirmDiscard: () => void;
  onKeepEditing: () => void;
  onAdd: (type: FeatureType) => void;
  onSave: () => void;
  onCancel: () => void;
  onDelete: () => void;
  onRedraw: () => void;
  onReloadLatest: () => void;
}

const KNOWN_FIELDS = ["name", "category", "description", "roadClass", "colour", "railwayId", "geometry"];

function geometryHint(type: FeatureType, empty: boolean): string {
  if (type === "station") {
    return empty ? "Click a point of a railway line on the map to place the station." : "Click another railway point to move the station.";
  }
  if (empty) {
    return type === "building"
      ? "Click on the map to add corners; click the first corner, double-click or press Enter to finish."
      : "Click on the map to add points (they snap to road and railway points); double-click or press Enter to finish.";
  }
  return "Drag points to move them, drag a middle handle to add a point, right-click a point to remove it.";
}

function FieldErrors({ messages }: { messages: string[] | undefined }) {
  if (!messages || messages.length === 0) return null;
  return (
    <ul className={styles.fieldErrors} role="alert">
      {messages.map((m, i) => (
        <li key={i}>{m}</li>
      ))}
    </ul>
  );
}

export default function EditorPanel(props: EditorPanelProps) {
  const { state, dispatch, allFeatures, mapHint, discardPrompt } = props;
  const d = state.draft;
  const busy = state.phase === "saving" || state.phase === "deleting";

  const toolbar = (
    <div className={styles.toolbar} role="toolbar" aria-label="Add feature">
      {FEATURE_TYPES.map((t) => (
        <button key={t} type="button" className={styles.toolButton} onClick={() => props.onAdd(t)} disabled={busy}>
          + {TYPE_LABELS[t]}
        </button>
      ))}
    </div>
  );

  const discard = discardPrompt && (
    <div className={styles.prompt} role="alertdialog" aria-label="Unsaved changes">
      <p>You have unsaved changes.</p>
      <div className={styles.buttonRow}>
        <button type="button" className={styles.dangerButton} onClick={props.onConfirmDiscard}>
          Discard changes
        </button>
        <button type="button" className={styles.secondaryButton} onClick={props.onKeepEditing}>
          Keep editing
        </button>
      </div>
    </div>
  );

  const notice = state.notice;
  const noticeView =
    notice === null ? null : notice.kind === "conflict" ? (
      <div className={styles.prompt} role="alert">
        <p>
          {notice.current
            ? `Changed by ${notice.current.updatedBy.name} at ${formatTimestamp(notice.current.updatedAt)}.`
            : "Someone else changed this feature."}{" "}
          Your version was not saved.
        </p>
        <div className={styles.buttonRow}>
          <button type="button" className={styles.primaryButton} onClick={props.onReloadLatest}>
            Reload latest
          </button>
          <button type="button" className={styles.secondaryButton} onClick={() => dispatch({ type: "dismiss_notice" })}>
            Keep my edits
          </button>
        </div>
      </div>
    ) : (
      <p className={notice.kind === "error" ? styles.errorText : styles.success} role={notice.kind === "error" ? "alert" : "status"}>
        {notice.text}
      </p>
    );

  if (!d) {
    return (
      <section className={styles.editor} aria-label="Editor">
        {toolbar}
        {discard}
        {noticeView}
        <p className={styles.hint}>Select a feature on the map (or search) to edit it, or add a new one.</p>
      </section>
    );
  }

  const input = draftToInput(d);
  const railwayLookup = (id: string) => allFeatures.find((f) => f.id === id && f.type === "railway")?.geometry;
  const clientDetails = state.showClientErrors ? validateInput(input, railwayLookup) : [];
  const errors = detailsByField([...clientDetails, ...state.serverDetails], KNOWN_FIELDS);
  const dirty = isDirty(d);
  const f = d.fields;
  const set = (field: keyof typeof f) => (e: { target: { value: string } }) => dispatch({ type: "set_field", field, value: e.target.value });
  const stationRailways = d.type === "station" && d.geometry[0] ? railwaysAtVertex(d.geometry[0], allFeatures) : [];
  const railwayName = (id: string) => {
    const r = allFeatures.find((x) => x.id === id);
    return r ? displayName(r) : id;
  };
  const nameId = `edit-name-${d.key}`;

  return (
    <section className={styles.editor} aria-label="Editor">
      {toolbar}
      {discard}
      <form
        className={styles.editForm}
        onSubmit={(e) => {
          e.preventDefault();
          props.onSave();
        }}
      >
        <div className={styles.editHeader}>
          <h2>{d.featureId ? `Edit ${TYPE_LABELS[d.type].toLowerCase()}` : `New ${TYPE_LABELS[d.type].toLowerCase()}`}</h2>
          {dirty && <span className={styles.badge}>Unsaved</span>}
        </div>
        {noticeView}
        <FieldErrors messages={errors.get("")} />

        <div className={styles.geometryBox}>
          <p className={styles.hint}>{geometryHint(d.type, d.geometry.length === 0)}</p>
          {mapHint && (
            <p className={styles.warn} role="status">
              {mapHint}
            </p>
          )}
          <p className={styles.muted}>
            {d.geometry.length === 0
              ? "No points yet"
              : d.type === "station"
                ? `At ${d.geometry[0]!.x}, ${d.geometry[0]!.z}`
                : `${d.geometry.length} point${d.geometry.length === 1 ? "" : "s"}`}
            {d.type !== "station" && d.geometry.length > 0 && (
              <>
                {" · "}
                <button type="button" className={styles.linkButton} onClick={props.onRedraw} disabled={busy}>
                  Redraw
                </button>
              </>
            )}
          </p>
          <FieldErrors messages={errors.get("geometry")} />
        </div>

        <label className={styles.formField} htmlFor={nameId}>
          <span>
            Name{d.type === "building" ? " (optional)" : ""}
          </span>
          <input id={nameId} value={f.name} onChange={set("name")} maxLength={MAX_NAME_LENGTH * 2} disabled={busy} autoComplete="off" />
          <FieldErrors messages={errors.get("name")} />
        </label>

        {d.type === "building" && (
          <>
            <label className={styles.formField}>
              <span>Category</span>
              <select value={f.category} onChange={set("category")} disabled={busy}>
                {BUILDING_CATEGORIES.map((c) => (
                  <option key={c} value={c}>
                    {CATEGORY_LABELS[c]}
                  </option>
                ))}
              </select>
              <FieldErrors messages={errors.get("category")} />
            </label>
            <label className={styles.formField}>
              <span>Description</span>
              <textarea value={f.description} onChange={set("description")} rows={3} maxLength={MAX_DESCRIPTION_LENGTH * 2} disabled={busy} />
              <FieldErrors messages={errors.get("description")} />
            </label>
          </>
        )}

        {d.type === "road" && (
          <label className={styles.formField}>
            <span>Road class</span>
            <select value={f.roadClass} onChange={set("roadClass")} disabled={busy}>
              {ROAD_CLASSES.map((c) => (
                <option key={c} value={c}>
                  {ROAD_CLASS_LABELS[c]}
                </option>
              ))}
            </select>
            <FieldErrors messages={errors.get("roadClass")} />
          </label>
        )}

        {d.type === "railway" && (
          <div className={styles.formField}>
            <span>Colour</span>
            <div className={styles.colourRow}>
              <input
                type="color"
                aria-label="Pick colour"
                value={/^#[0-9a-fA-F]{6}$/.test(f.colour) ? f.colour.toLowerCase() : "#000000"}
                onChange={set("colour")}
                disabled={busy}
              />
              <input aria-label="Colour hex" value={f.colour} onChange={set("colour")} maxLength={7} disabled={busy} autoComplete="off" />
            </div>
            <FieldErrors messages={errors.get("colour")} />
          </div>
        )}

        {d.type === "station" && (
          <div className={styles.formField}>
            <span>Railway</span>
            {stationRailways.length > 1 ? (
              <select value={f.railwayId} onChange={set("railwayId")} disabled={busy} aria-label="Railway at this point">
                {stationRailways.map((id) => (
                  <option key={id} value={id}>
                    {railwayName(id)}
                  </option>
                ))}
              </select>
            ) : (
              <p className={styles.readOnly}>{f.railwayId ? railwayName(f.railwayId) : "Set by placing the station on a railway"}</p>
            )}
            <FieldErrors messages={errors.get("railwayId")} />
          </div>
        )}

        {state.phase === "confirm_delete" ? (
          <div className={styles.prompt} role="alertdialog" aria-label="Confirm delete">
            <p>Delete “{d.initial.fields.name.trim() || `this ${TYPE_LABELS[d.type].toLowerCase()}`}”? This cannot be undone here.</p>
            <div className={styles.buttonRow}>
              <button type="button" className={styles.dangerButton} onClick={props.onDelete}>
                Delete
              </button>
              <button type="button" className={styles.secondaryButton} onClick={() => dispatch({ type: "cancel_delete" })}>
                Keep
              </button>
            </div>
          </div>
        ) : (
          <div className={styles.buttonRow}>
            <button type="submit" className={styles.primaryButton} disabled={busy || (!dirty && d.featureId !== null)}>
              {state.phase === "saving" ? "Saving…" : d.featureId ? "Save" : "Create"}
            </button>
            <button type="button" className={styles.secondaryButton} onClick={props.onCancel} disabled={busy}>
              {dirty ? "Cancel" : "Close"}
            </button>
            {d.featureId && (
              <button
                type="button"
                className={`${styles.dangerButton} ${styles.pushRight}`}
                onClick={() => dispatch({ type: "ask_delete" })}
                disabled={busy}
              >
                {state.phase === "deleting" ? "Deleting…" : "Delete"}
              </button>
            )}
          </div>
        )}
      </form>
    </section>
  );
}
