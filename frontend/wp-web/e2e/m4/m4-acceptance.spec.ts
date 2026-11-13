import { expect, test } from "@playwright/test";
import { randomBytes } from "node:crypto";
import { readFile } from "node:fs/promises";
import {
  AccountPage,
  AdminUsersPage,
  ClassFrameEditor,
  ENGLISH_LABEL,
  forceZhLocale,
  HANDLING_UNIT_IRI,
  HistoryPage,
  LoginPage,
  PALLET_IRI,
  ProjectListPage,
  SEARCH_QUERY,
  SettingsPage,
  SUBCLASS_NAME,
  WAREHOUSE_TTL,
  WorkspacePage,
} from "./pages";

const ADMIN_USER = "m4admin";
const ADMIN_PASSWORD = process.env.WP_M4_ADMIN_PASSWORD ?? "";
const EDITOR_USER = "m4editor";
const EDITOR_PASSWORD = `Ed1t-${randomBytes(12).toString("base64url")}`;
const PROJECT_NAME = "Warehouse";

test.describe.configure({ mode: "serial" });

test("M4 web acceptance: dual-browser edit loop with live updates", async ({
  browser,
}) => {
  expect(ADMIN_PASSWORD.length).toBeGreaterThan(0);

  const adminContext = await browser.newContext({ locale: "zh-CN" });
  const editorContext = await browser.newContext({ locale: "zh-CN" });
  const adminPage = await adminContext.newPage();
  const editorPage = await editorContext.newPage();
  await forceZhLocale(adminPage);
  await forceZhLocale(editorPage);

  const adminLogin = new LoginPage(adminPage);
  const editorLogin = new LoginPage(editorPage);
  const adminUsers = new AdminUsersPage(adminPage);
  const adminProjects = new ProjectListPage(adminPage);
  const adminSettings = new SettingsPage(adminPage);
  const adminWorkspace = new WorkspacePage(adminPage);
  const adminFrame = new ClassFrameEditor(adminPage);
  const editorAccount = new AccountPage(editorPage);
  const editorWorkspace = new WorkspacePage(editorPage);
  const editorFrame = new ClassFrameEditor(editorPage);
  const editorHistory = new HistoryPage(editorPage);

  // 1. Admin creates editor user.
  await adminLogin.login(ADMIN_USER, ADMIN_PASSWORD);
  await adminUsers.openFromUserMenu();
  await adminUsers.createUser({
    userId: EDITOR_USER,
    displayName: "M4 Editor",
    email: "m4editor@example.invalid",
    initialPassword: EDITOR_PASSWORD,
  });

  // 2. Admin uploads warehouse ontology.
  const projectId = await adminProjects.uploadOntology(
    WAREHOUSE_TTL,
    PROJECT_NAME,
  );

  // 3. Display names (empty / zh / en) + grant CAN_EDIT.
  await adminSettings.openDisplayNames(projectId);
  await adminSettings.setDisplayNamesEmptyZhEn();
  await adminSettings.openMembers(projectId);
  await adminSettings.addEditor(EDITOR_USER);

  // 4. Editor creates API key (plaintext once).
  await editorLogin.login(EDITOR_USER, EDITOR_PASSWORD);
  await editorAccount.openFromUserMenu();
  await editorAccount.createApiKeyOnce("m4-acceptance");

  // 5. Both open class hierarchy; expand HandlingUnit; Pallet is a leaf.
  await adminWorkspace.openClassHierarchy(projectId);
  await editorWorkspace.openClassHierarchy(projectId);
  await expandToPallet(adminWorkspace);
  await expandToPallet(editorWorkspace);
  await adminWorkspace.expectLeaf("托盘", PALLET_IRI);
  await editorWorkspace.expectLeaf("托盘", PALLET_IRI);

  // 6. Editor creates subclass; Admin tree updates live within 10s.
  await editorWorkspace.createSubclassViaContextMenu(
    "托盘",
    PALLET_IRI,
    SUBCLASS_NAME,
  );
  await adminWorkspace.expectExpandableWithin("托盘", PALLET_IRI, 10_000);
  await adminWorkspace.expandNode("托盘", PALLET_IRI);
  await expect(adminWorkspace.nodeByLabel(SUBCLASS_NAME)).toBeVisible({
    timeout: 10_000,
  });

  // 7. Editor adds English label; Admin sees it on the same entity.
  await editorWorkspace.searchAndSelect(SUBCLASS_NAME, SUBCLASS_NAME);
  await expect(editorPage.getByTestId("class-frame-editor")).toBeVisible({
    timeout: 30_000,
  });
  await editorFrame.addEnglishLabel(ENGLISH_LABEL);
  await adminWorkspace.searchAndSelect(SUBCLASS_NAME, SUBCLASS_NAME);
  await expect(adminPage.getByTestId("class-frame-editor")).toBeVisible({
    timeout: 30_000,
  });
  await adminFrame.expectLabelVisible(ENGLISH_LABEL);

  // 8. Editor searches pallet and selects 周转托盘.
  await editorWorkspace.searchAndSelect(SEARCH_QUERY, SUBCLASS_NAME);

  // 9. History shows create + edit; revert latest.
  await editorWorkspace.openHistory();
  await editorHistory.expectLatestDescriptions(
    `Created class ${SUBCLASS_NAME}`,
    `Edited class ${SUBCLASS_NAME}`,
  );
  await editorHistory.revertLatest();

  // 10. Download Turtle after revert; search no longer hits English label class.
  await editorWorkspace.openClassHierarchy(projectId);
  const download = await editorWorkspace.downloadTurtle();
  expect(download.suggestedFilename()).toMatch(/\.ttl$/i);
  const turtlePath = await download.path();
  expect(turtlePath).toBeTruthy();
  const turtle = await readFile(turtlePath!, "utf8");
  expect(turtle).toContain(SUBCLASS_NAME);
  expect(turtle).toContain("托盘");
  expect(turtle).not.toContain(ENGLISH_LABEL);
  await editorWorkspace.expectSearchMiss(SEARCH_QUERY, SUBCLASS_NAME);

  // 11. Admin trashes project; editor reload shows trashed message.
  const editorProjectUrl = editorPage.url();
  await adminProjects.moveToTrash(PROJECT_NAME);
  await adminProjects.expectProjectAbsent(PROJECT_NAME);
  await adminProjects.expectProjectInTrash(PROJECT_NAME);
  await editorPage.goto(editorProjectUrl);
  await editorPage.reload();
  await editorWorkspace.expectTrashedMessage();

  await adminContext.close();
  await editorContext.close();
});

async function expandToPallet(workspace: WorkspacePage): Promise<void> {
  // el-tree-v2 virtualizes rows; deep children may be off-screen. Use search
  // locate (same as product) then expand the HandlingUnit parent on the path.
  await workspace.searchAndSelect("托盘", "托盘");
  await workspace.expandNode("搬运单元", HANDLING_UNIT_IRI);
  await expect(workspace.nodeByLabel("托盘", PALLET_IRI)).toBeVisible({
    timeout: 30_000,
  });
}
