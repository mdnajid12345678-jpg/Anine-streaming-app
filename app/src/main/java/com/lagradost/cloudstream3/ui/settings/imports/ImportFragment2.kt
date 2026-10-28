package com.lagradost.cloudstream3.ui.settings.imports

import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.mihon.presentation.settings.SearchableSettings

/** Glue between the navigation graph and the Compose import dashboard. */
class ImportFragment2 : BaseComposeFragment(), SearchableSettings by ImportScreen
