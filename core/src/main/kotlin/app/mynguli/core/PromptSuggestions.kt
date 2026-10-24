package app.mynguli.core

/** Editorial starter examples. No private identity or invented user context. */
object PromptSuggestions {
    fun forModule(kind: String?, state: FeedState?, sw: Boolean): List<String> {
        val prompts = when(kind) {
            "weather" -> listOf("Will it rain today?" to "Leo mvua itanyesha?", "Help me plan tomorrow" to "Nisaidie kupanga kesho")
            "music" -> listOf("Find music I might enjoy" to "Nipendekezee muziki ninaoweza kupenda", "Find a performance to watch" to "Nipatie video nzuri ya muziki", "Find a lesson I can practise" to "Nipatie somo la kufanya mazoezi")
            "entertainment" -> listOf("What should I watch tonight?" to "Nitazame filamu gani leo?", "Find something like my favourites" to "Nipatie inayofanana na ninazopenda")
            "technology" -> listOf("What changed in AI this week?" to "Kuna jipya gani kwenye AI wiki hii?", "Find a tool worth trying" to "Nipatie zana inayoweza kunisaidia")
            "security" -> listOf("Which updates need attention?" to "Ni masasisho gani yanahitaji kuangaliwa?", "Explain this security issue" to "Nielezee tatizo hili la usalama")
            "learning" -> listOf("Teach me something useful" to "Nifundishe kitu cha kunisaidia", "Give me a short practice task" to "Nipe zoezi fupi")
            "opportunities", "scholarships" -> listOf("Find opportunities that fit me" to "Nitafutie fursa zinazonifaa", "Which deadlines are approaching?" to "Fursa zipi zinakaribia kufungwa?")
            "github" -> listOf("Find an open-source project for me" to "Nipatie mradi wa programu huria unaonifaa", "Show a useful recent release" to "Nionyeshe toleo jipya linalofaa")
            "events", "travel" -> listOf("What can I do this weekend?" to "Nifanye nini mwisho wa wiki?", "Find something nearby" to "Nitafutie kilicho karibu nami")
            "science" -> listOf("Explain a recent discovery" to "Nielezee utafiti mpya", "Show the evidence and limits" to "Nionyeshe ushahidi na mipaka yake")
            "horoscope" -> listOf("Give me a light reading for today" to "Nipe nyota ya leo kwa burudani")
            "media" -> listOf("Find a short video worth watching" to "Nipatie video fupi yenye maana", "Show me how it works" to "Nionyeshe inavyofanya kazi")
            "business" -> listOf("What affects my work this week?" to "Nini kinaathiri kazi yangu wiki hii?", "Explain a useful business update" to "Nielezee taarifa muhimu ya biashara")
            else -> listOf("What matters for me today?" to "Leo kuna nini kinachonihusu?", "Find something useful to learn" to "Nipatie kitu cha kujifunza", "Explain something simply" to "Nielezee jambo kwa lugha rahisi")
        }
        val result = prompts.map { if(sw) it.second else it.first }.toMutableList()
        if(kind == null) state?.modules?.filter { it.enabled && it.kind in setOf("weather", "music", "entertainment", "technology") }?.take(3)?.forEach {
            result += forModule(it.kind, null, sw).first()
        }
        return result.distinct().take(6)
    }
}
