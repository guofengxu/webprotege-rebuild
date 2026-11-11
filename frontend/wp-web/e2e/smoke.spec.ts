import { expect, test } from "@playwright/test";

test("home page shows the editor title without a backend", async ({ page }) => {
  await page.route("**/api/**", (route) => route.fulfill({ status: 404 }));
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "WebProtégé 本体编辑器" }),
  ).toBeVisible();
});
