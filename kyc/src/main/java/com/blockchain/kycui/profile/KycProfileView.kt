package com.blockchain.kycui.profile

import com.blockchain.kycui.profile.models.ProfileModel
import piuk.blockchain.androidcoreui.ui.base.View
import java.util.Calendar

interface KycProfileView : View {

    val firstName: String

    val lastName: String

    val countryCode: String

    var dateOfBirth: Calendar?

    fun setButtonEnabled(enabled: Boolean)

    fun continueSignUp(profileModel: ProfileModel)

    fun showErrorToast(message: String)

    fun dismissProgressDialog()

    fun showProgressDialog()

    fun restoreUiState(
        firstName: String,
        lastName: String,
        displayDob: String,
        dobCalendar: Calendar
    )
}