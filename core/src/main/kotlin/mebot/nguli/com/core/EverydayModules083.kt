package mebot.nguli.com.core

/** Stable additions: upgrading installs them paused and never broadens a saved note's scope. */
object EverydayModules083 {
    val presets = listOf(
        ModulePreset("food", "life", "Practical recipes, food preparation and meal ideas suited to approved interests and locally available ingredients.",
            "Use original recipes or trustworthy food-safety sources. Give clear ingredients and practical steps. Distinguish taste suggestions from nutrition claims; never promise health effects or ignore allergies. State costs only when sourced and dated."),
        ModulePreset("farming", "growth", "Useful agriculture, livestock and gardening information for the configured region and the crops or animals the user chooses.",
            "Prefer agricultural extension services, research institutes and official weather or market sources. State regional applicability and dates. Do not invent market prices or advise hazardous pesticide use; link official safety guidance."),
        ModulePreset("family", "life", "Practical family activities, learning resources and household routines matched to explicitly selected interests.",
            "Offer inclusive, age-appropriate general resources from credible educators or public services. Do not infer parenthood, children, relationship status or private family details. Do not request children's identifying information."),
        ModulePreset("sport", "life", "Verified sports results, fixtures and coverage for the user's chosen teams, sports and region.",
            "Use official leagues, clubs or reliable sports publishers. Verify event dates and timezones; clearly separate completed results from upcoming fixtures. No gambling tips or fabricated live scores."),
        ModulePreset("home", "life", "Useful home care, simple repairs, organizing and consumer product-care information.",
            "Prefer manufacturer instructions or qualified public guidance. Give achievable low-risk steps and clear safety boundaries. Electrical, gas and structural hazards require qualified help; do not invent safety assurances or product prices."),
        ModulePreset("wellbeing", "life", "General wellbeing education about sleep, movement, rest and everyday habits from credible public-health sources.",
            "Use authoritative public-health sources and distinguish evidence from suggestions. General education only, not diagnosis, treatment or personalized medical advice. Never infer a condition, recommend medication changes or promise health outcomes."),
    )
}
