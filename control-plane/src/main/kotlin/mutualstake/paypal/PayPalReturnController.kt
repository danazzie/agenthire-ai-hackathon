package mutualstake.paypal

import mutualstake.config.MutualStakeProperties
import mutualstake.hold.HoldRepository
import mutualstake.outbox.OutboxRepository
import mutualstake.outbox.OutboxType
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.util.HtmlUtils
import java.util.UUID

/**
 * Where PayPal sends the payer after the approval page. It nudges a check with PayPal and says plainly
 * that it decides nothing: the hold is confirmed by PayPal's own answer, not by this redirect.
 */
@Controller
class PayPalReturnController(private val holds: HoldRepository, private val outbox: OutboxRepository, private val props: MutualStakeProperties) {

    @GetMapping("/paypal/return", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun returned(@RequestParam hold: UUID): String {
        val found = holds.find(hold)
        found?.let { outbox.enqueue(it.id, OutboxType.CHECK_ORDER) }
        return page(
            "Approved with PayPal",
            "Nothing has been charged. HireMind is now asking PayPal whether the ${found?.side?.label ?: "hold"} is in place; this page does not decide that.",
            found?.contractId,
        )
    }

    @GetMapping("/paypal/cancel", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun cancelled(@RequestParam hold: UUID): String =
        page("Approval cancelled", "Nothing was held. The approval link stays valid if you change your mind.", holds.find(hold)?.contractId)

    private fun page(title: String, body: String, contractId: UUID?): String {
        val back = if (props.webUrl.isNotBlank() && contractId != null) {
            """<p><a href="${HtmlUtils.htmlEscape(props.webUrl.trimEnd('/'))}/contracts/$contractId">Back to the deal</a></p>"""
        } else {
            "<p>You can close this tab.</p>"
        }
        return """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>HireMind</title></head><body style="font:16px/1.5 system-ui,sans-serif;margin:3rem auto;max-width:34rem;padding:0 1rem">
<h1 style="font-size:1.4rem">${HtmlUtils.htmlEscape(title)}</h1><p>${HtmlUtils.htmlEscape(body)}</p>$back</body></html>"""
    }
}
