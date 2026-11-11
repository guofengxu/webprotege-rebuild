import type { components } from "./schema";

export type { components, paths } from "./schema";

type Schemas = components["schemas"];

export type AdminUserPage = Schemas["AdminUserPage"];
export type AdminUserResponse = Schemas["AdminUserResponse"];
export type AnnotationPropertyFrame = Schemas["AnnotationPropertyFrame"];
export type AnnotationPropertyFrameUpdateResult = Schemas["AnnotationPropertyFrameUpdateResult"];
export type AnnotationValue = Schemas["AnnotationValue"];
export type ApiKeyInfo = Schemas["ApiKeyInfo"];
export type AuthUserResponse = Schemas["AuthUserResponse"];
export type BrowserTextChanged = Schemas["BrowserTextChanged"];
export type ClassFrame = Schemas["ClassFrame"];
export type ClassFrameUpdateResult = Schemas["ClassFrameUpdateResult"];
export type CreateApiKeyRequest = Schemas["CreateApiKeyRequest"];
export type CreateEntitiesRequest = Schemas["CreateEntitiesRequest"];
export type CreateProjectRequest = Schemas["CreateProjectRequest"];
export type CreateUserRequest = Schemas["CreateUserRequest"];
export type CreatedEntities = Schemas["CreatedEntities"];
export type DataPropertyAssertion = Schemas["DataPropertyAssertion"];
export type DataPropertyFrame = Schemas["DataPropertyFrame"];
export type DataPropertyFrameUpdateResult = Schemas["DataPropertyFrameUpdateResult"];
export type DeleteEntitiesRequest = Schemas["DeleteEntitiesRequest"];
export type DeviceSession = Schemas["DeviceSession"];
export type DisplayNameSetting = Schemas["DisplayNameSetting"];
export type EntityCrudKitSettings = Schemas["EntityCrudKitSettings"];
export type EntityFrameChanged = Schemas["EntityFrameChanged"];
export type EntityHierarchyChanged = Schemas["EntityHierarchyChanged"];
export type EntityNode = Schemas["EntityNode"];
export type IndividualPage = Schemas["IndividualPage"];
export type IriValue = Schemas["IriValue"];
export type IssueTokenRequest = Schemas["IssueTokenRequest"];
export type IssuedApiKeyResponse = Schemas["IssuedApiKeyResponse"];
export type IssuedTokensResponse = Schemas["IssuedTokensResponse"];
export type LiteralValue = Schemas["LiteralValue"];
export type LoginRequest = Schemas["LoginRequest"];
export type MemberResponse = Schemas["MemberResponse"];
export type NamedIndividualFrame = Schemas["NamedIndividualFrame"];
export type NamedIndividualFrameUpdateResult = Schemas["NamedIndividualFrameUpdateResult"];
export type NewEntitySettings = Schemas["NewEntitySettings"];
export type ObjectPropertyAssertion = Schemas["ObjectPropertyAssertion"];
export type ObjectPropertyFrame = Schemas["ObjectPropertyFrame"];
export type ObjectPropertyFrameUpdateResult = Schemas["ObjectPropertyFrameUpdateResult"];
export type PermissionsChanged = Schemas["PermissionsChanged"];
export type PrefixSettings = Schemas["PrefixSettings"];
export type ProblemDetail = Schemas["ProblemDetail"];
export type ProjectChanged = Schemas["ProjectChanged"];
export type ProjectResponse = Schemas["ProjectResponse"];
export type PropertyValue = Schemas["PropertyValue"];
export type RefreshTokenRequest = Schemas["RefreshTokenRequest"];
export type ResetPasswordRequest = Schemas["ResetPasswordRequest"];
export type ResyncSignal = Schemas["ResyncSignal"];
export type RevisionChange = Schemas["RevisionChange"];
export type RevisionDiff = Schemas["RevisionDiff"];
export type RevisionPage = Schemas["RevisionPage"];
export type RevisionResponse = Schemas["RevisionResponse"];
export type RevisionSummary = Schemas["RevisionSummary"];
export type RoleRequest = Schemas["RoleRequest"];
export type SetAppRolesRequest = Schemas["SetAppRolesRequest"];
export type UpdateAnnotationPropertyFrameRequest = Schemas["UpdateAnnotationPropertyFrameRequest"];
export type UpdateClassFrameRequest = Schemas["UpdateClassFrameRequest"];
export type UpdateDataPropertyFrameRequest = Schemas["UpdateDataPropertyFrameRequest"];
export type UpdateNamedIndividualFrameRequest = Schemas["UpdateNamedIndividualFrameRequest"];
export type UpdateObjectPropertyFrameRequest = Schemas["UpdateObjectPropertyFrameRequest"];
export type UpdateProjectRequest = Schemas["UpdateProjectRequest"];
export type ValueNode = Schemas["ValueNode"];

/** Five project events. Matches Java {@code ProjectEvent}; resync is not one of them. */
export type ProjectEvent =
  | ProjectChanged
  | EntityHierarchyChanged
  | EntityFrameChanged
  | BrowserTextChanged
  | PermissionsChanged;

/** `data` payload of the project event stream, including a resync signal. */
export type ProjectStreamMessage = ProjectEvent | ResyncSignal;
