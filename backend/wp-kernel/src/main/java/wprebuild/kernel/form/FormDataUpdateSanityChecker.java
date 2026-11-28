package wprebuild.kernel.form;

import java.util.Map;
import java.util.Objects;

/**
 * Ensures pristine and edited form maps are paired by {@link FormId} with equal descriptors
 * (legacy {@code FormDataUpdateSanityChecker}).
 */
public final class FormDataUpdateSanityChecker {

    private FormDataUpdateSanityChecker() {}

    public static void check(Map<FormId, FormData> pristine, Map<FormId, FormData> edited) {
        Objects.requireNonNull(pristine, "pristine");
        Objects.requireNonNull(edited, "edited");
        for (FormId formId : pristine.keySet()) {
            if (!edited.containsKey(formId)) {
                throw new IllegalArgumentException("pristine form not found in edited forms: " + formId.value());
            }
        }
        for (Map.Entry<FormId, FormData> entry : edited.entrySet()) {
            FormId formId = entry.getKey();
            FormData editedData = entry.getValue();
            FormData pristineData = pristine.get(formId);
            if (pristineData == null) {
                throw new IllegalArgumentException("edited form not found in pristine forms: " + formId.value());
            }
            if (!formId.equals(editedData.formId()) || !formId.equals(pristineData.formId())) {
                throw new IllegalArgumentException("map key does not match FormData.formId for " + formId.value());
            }
            if (!pristineData.descriptor().equals(editedData.descriptor())) {
                throw new IllegalArgumentException(
                        "pristine and edited form descriptors are not equal for " + formId.value());
            }
        }
    }
}
