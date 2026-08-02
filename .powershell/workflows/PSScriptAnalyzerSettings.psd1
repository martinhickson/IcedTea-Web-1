# Settings for host Windows PowerShell workflows.
# These scripts are CLI drivers (Trunk-style): Write-Host progress, Ensure-* helpers,
# and state-changing install steps without ShouldProcess prompts.
@{
    Severity     = @('Error', 'Warning', 'Information')
    ExcludeRules = @(
        'PSAvoidUsingWriteHost'
        'PSUseApprovedVerbs'
        'PSUseShouldProcessForStateChangingFunctions'
        'PSUseSingularNouns'
        # Script-level param() values are consumed in nested functions; analyzer
        # false-positives them as unused in these driver scripts.
        'PSReviewUnusedParameter'
    )
}
