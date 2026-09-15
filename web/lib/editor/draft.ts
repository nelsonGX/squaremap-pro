/**
 * Editor draft state: a reducer over the feature being created or edited, plus request building.
 * Pure (no React, no Leaflet).
 */
import type {
  BuildingCategory,
  Feature,
  FeatureInput,
  FeatureType,
  RoadClass,
  ValidationDetail,
  XZ,
} from "../api/types";
import { HEX_COLOUR } from "../api/guards";
import { buildingStyle, railwayStyle, roadStyle, stationStyle, UNKNOWN_RAILWAY_COLOUR, type FeatureStyle } from "../features/styles";
import { cleanGeometry, sameGeometry } from "./geometry";

export interface DraftFields {
  name: string;
  category: BuildingCategory;
  description: string;
  roadClass: RoadClass;
  colour: string;
  railwayId: string;
}

export const DEFAULT_FIELDS: DraftFields = {
  name: "",
  category: "residential",
  description: "",
  roadClass: "street",
  colour: "#d62828",
  railwayId: "",
};

export interface Draft {
  /** Unique per started draft; map layers are rebuilt when it changes. */
  key: number;
  type: FeatureType;
  /** null while creating. */
  featureId: string | null;
  revision: number | null;
  geometry: XZ[];
  fields: DraftFields;
  initial: { geometry: XZ[]; fields: DraftFields };
}

export type EditorNotice =
  | { kind: "saved"; text: string }
  | { kind: "deleted"; text: string }
  | { kind: "error"; text: string }
  /** 409: someone else saved first. `current` is the latest version when known (null = deleted / unknown). */
  | { kind: "conflict"; current: Feature | null };

export type EditorPhase = "idle" | "saving" | "confirm_delete" | "deleting";

export interface EditorState {
  draft: Draft | null;
  phase: EditorPhase;
  /** Validation details from the server's last 400 response. */
  serverDetails: ValidationDetail[];
  /** Show client-side validation messages (after the first save attempt). */
  showClientErrors: boolean;
  notice: EditorNotice | null;
  nextKey: number;
}

export const INITIAL_EDITOR_STATE: EditorState = {
  draft: null,
  phase: "idle",
  serverDetails: [],
  showClientErrors: false,
  notice: null,
  nextKey: 1,
};

export interface SaveFailure {
  status: number;
  message: string;
  details: ValidationDetail[];
  current?: Feature | null;
}

export type EditorAction =
  | { type: "start_create"; featureType: FeatureType; fields?: Partial<DraftFields> }
  | { type: "start_edit"; feature: Feature }
  | { type: "set_field"; field: keyof DraftFields; value: string }
  | { type: "set_geometry"; geometry: XZ[] }
  /** Station placement: geometry and railway together. */
  | { type: "place_station"; vertex: XZ; railwayId: string }
  | { type: "close" }
  | { type: "revert" }
  | { type: "save_invalid" }
  | { type: "save_start" }
  | { type: "save_ok"; feature: Feature; created: boolean }
  | { type: "save_error"; failure: SaveFailure }
  | { type: "ask_delete" }
  | { type: "cancel_delete" }
  | { type: "delete_start" }
  | { type: "delete_ok" }
  /** Discard local edits and continue from the latest server version (null = it was deleted). */
  | { type: "reload_latest"; feature: Feature | null }
  | { type: "dismiss_notice" };

export function fieldsOf(f: Feature): DraftFields {
  const fields: DraftFields = { ...DEFAULT_FIELDS, name: f.name };
  switch (f.type) {
    case "building":
      return { ...fields, category: f.props.category, description: f.props.description };
    case "road":
      return { ...fields, roadClass: f.props.roadClass };
    case "railway":
      return { ...fields, colour: f.props.colour };
    case "station":
      return { ...fields, railwayId: f.props.railwayId };
  }
}

function draftFor(state: EditorState, f: Feature): Draft {
  const fields = fieldsOf(f);
  const geometry = f.geometry.map((p) => ({ x: p.x, z: p.z }));
  return { key: state.nextKey, type: f.type, featureId: f.id, revision: f.revision, geometry, fields, initial: { geometry, fields } };
}

const clean = { phase: "idle" as const, serverDetails: [], showClientErrors: false };

/** Fields relevant for a type (others are ignored for dirty checks and requests). */
export function relevantFields(type: FeatureType): (keyof DraftFields)[] {
  switch (type) {
    case "building":
      return ["name", "category", "description"];
    case "road":
      return ["name", "roadClass"];
    case "railway":
      return ["name", "colour"];
    case "station":
      return ["name", "railwayId"];
  }
}

export function isDirty(d: Draft | null): boolean {
  if (!d) return false;
  if (!sameGeometry(d.geometry, d.initial.geometry)) return true;
  return relevantFields(d.type).some((k) => d.fields[k] !== d.initial.fields[k]);
}

export function editorReducer(state: EditorState, action: EditorAction): EditorState {
  const d = state.draft;
  switch (action.type) {
    case "start_create": {
      const fields = { ...DEFAULT_FIELDS, ...action.fields };
      const draft: Draft = {
        key: state.nextKey,
        type: action.featureType,
        featureId: null,
        revision: null,
        geometry: [],
        fields,
        initial: { geometry: [], fields },
      };
      return { ...state, ...clean, draft, notice: null, nextKey: state.nextKey + 1 };
    }
    case "start_edit":
      return { ...state, ...clean, draft: draftFor(state, action.feature), notice: null, nextKey: state.nextKey + 1 };
    case "set_field":
      if (!d || state.phase === "saving" || state.phase === "deleting") return state;
      return { ...state, draft: { ...d, fields: { ...d.fields, [action.field]: action.value } } };
    case "set_geometry":
      if (!d || state.phase === "saving" || state.phase === "deleting") return state;
      return { ...state, draft: { ...d, geometry: cleanGeometry(d.type, action.geometry) } };
    case "place_station":
      if (!d || d.type !== "station" || state.phase === "saving" || state.phase === "deleting") return state;
      return {
        ...state,
        draft: { ...d, geometry: [{ x: action.vertex.x, z: action.vertex.z }], fields: { ...d.fields, railwayId: action.railwayId } },
      };
    case "close":
      return { ...state, ...clean, draft: null };
    case "revert":
      if (!d) return state;
      return { ...state, ...clean, draft: { ...d, geometry: d.initial.geometry, fields: d.initial.fields } };
    case "save_invalid":
      return { ...state, showClientErrors: true };
    case "save_start":
      if (!d || state.phase !== "idle") return state;
      return { ...state, phase: "saving", serverDetails: [], showClientErrors: true, notice: null };
    case "save_ok": {
      const draft = draftFor(state, action.feature);
      return {
        ...state,
        ...clean,
        draft,
        notice: { kind: "saved", text: action.created ? "Created" : "Saved" },
        nextKey: state.nextKey + 1,
      };
    }
    case "save_error": {
      const f = action.failure;
      if (f.status === 409) return { ...state, phase: "idle", notice: { kind: "conflict", current: f.current ?? null } };
      if (f.status === 400 && f.details.length > 0) {
        return { ...state, phase: "idle", serverDetails: f.details, notice: { kind: "error", text: "The server rejected the changes." } };
      }
      return { ...state, phase: "idle", notice: { kind: "error", text: f.message } };
    }
    case "ask_delete":
      if (!d || d.featureId === null || state.phase !== "idle") return state;
      return { ...state, phase: "confirm_delete" };
    case "cancel_delete":
      return state.phase === "confirm_delete" ? { ...state, phase: "idle" } : state;
    case "delete_start":
      return state.phase === "confirm_delete" ? { ...state, phase: "deleting", notice: null } : state;
    case "delete_ok":
      return { ...state, ...clean, draft: null, notice: { kind: "deleted", text: "Deleted" } };
    case "reload_latest":
      if (!action.feature) {
        return { ...state, ...clean, draft: null, notice: { kind: "error", text: "This feature was deleted by someone else." } };
      }
      return { ...state, ...clean, draft: draftFor(state, action.feature), notice: null, nextKey: state.nextKey + 1 };
    case "dismiss_notice":
      return { ...state, notice: null };
  }
}

/** Request body for the draft (name trimmed, only the type's props). */
export function draftToInput(d: Draft): FeatureInput {
  const name = d.fields.name.trim();
  const geometry = d.geometry.map((p) => ({ x: p.x, z: p.z }));
  switch (d.type) {
    case "building":
      return { type: "building", name, geometry, props: { category: d.fields.category, description: d.fields.description } };
    case "road":
      return { type: "road", name, geometry, props: { roadClass: d.fields.roadClass } };
    case "railway":
      return { type: "railway", name, geometry, props: { colour: d.fields.colour.toLowerCase() } };
    case "station":
      return { type: "station", name, geometry, props: { railwayId: d.fields.railwayId } };
  }
}

/** Map style for the draft, from its current (possibly unsaved) fields. */
export function draftStyle(d: Draft, railwayColours: ReadonlyMap<string, string>): FeatureStyle {
  switch (d.type) {
    case "building":
      return buildingStyle(d.fields.category);
    case "road":
      return roadStyle(d.fields.roadClass);
    case "railway":
      return railwayStyle(HEX_COLOUR.test(d.fields.colour) ? d.fields.colour : UNKNOWN_RAILWAY_COLOUR);
    case "station":
      return stationStyle(railwayColours.get(d.fields.railwayId) ?? UNKNOWN_RAILWAY_COLOUR);
  }
}
