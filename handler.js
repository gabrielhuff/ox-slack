const axios = require('axios');
const chrono = require('chrono-node')
const crawler = require('crawler-request')
const memoize = require("memoizee")
const qs = require('querystring')
const weekNumber = require('weeknumber').weekNumber

let SLACK_TOKEN = process.env.SLACK_TOKEN

const getMenuByDate = async function(dayOfMonth, weekOfYear, year) {

    // Get menu as text (return error string if unavailable)
    const possibleUrls = [
        `http://www.ox-linz.at/fileadmin/Mittagsmenue/${year}/OX_Linz_A5_Wochenmenue__KW${weekOfYear}.pdf`,
        `http://www.ox-linz.at/fileadmin/Mittagsmenue/${year}/OX_Linz_A5_Wochenmenue__KW${weekOfYear}_V2.pdf`,
        `http://www.ox-linz.at/fileadmin/Mittagsmenue/${year}/OX_Linz_A5_Wochenmenue_KW${weekOfYear}.pdf`,
        `http://www.ox-linz.at/fileadmin/Mittagsmenue/OX_Linz_A5_Wochenmenue__KW${weekOfYear}.pdf`,
        `http://www.ox-linz.at/fileadmin/Mittagsmenue/OX_Linz_A5_Wochenmenue_KW${weekOfYear}.pdf`
    ]
    let menuText = ""
    for (url of possibleUrls) {
        const response = await crawler(url)
        if (response.text != null) {
            menuText = response.text
            break
        }
    }

    // Parse menu
    const firstLinePattern = new RegExp(`.*// ${dayOfMonth}([. ]).*`, 'g')
    const lastLinePattern = new RegExp(`^\s*$|.*Änderungen vorbehalten!.*`, 'gm')
    let menuLines = menuText.split('\n').map(s => s.trim())
    const firstLineIndex = menuLines.findIndex(s => s.match(firstLinePattern))
    menuLines = menuLines.slice(firstLineIndex)
    const lastLineIndex = menuLines.findIndex(s => s.match(lastLinePattern))
    const dayMenuLines = menuLines.slice(0, lastLineIndex)
    if (dayMenuLines.length > 0) {
        dayMenuLines[0] = '*' + dayMenuLines[0] + '*'
    }
    const dayMenu = dayMenuLines.join('\n')

    if (!dayMenu) return "There's no menu available at the given date."

    // Return day menu
    return dayMenu
}

const getMenuByDateMemoized = memoize(getMenuByDate, { promise: true })

const getMenuByDateString = async function(dateString) {

    // Parse date (return error string if can't be parsed)
    const date = dateString ? chrono.parseDate(dateString) : new Date()
    if (date == null) return "The input date couldn't be parsed."
    const dayOfMonth = date.getDate()
    const weekOfYear = weekNumber(date)
    const year =  date.getFullYear()

    // Get and return menu
    return await getMenuByDateMemoized(dayOfMonth, weekOfYear, year)
}

module.exports.getMenu = getMenuByDateString

module.exports.endpoint = async (event, context, callback) => {
    // Parse form
    const { token, text, response_url } = qs.parse(event.body)

    // Fail if token is invalid
    if (SLACK_TOKEN != null && token != SLACK_TOKEN) {
        callback(null, { statusCode: 403 })
        return
    }

    // Respond right away with 200 (the actual message will be sent asynchronously to the response URL)
    callback(null, { statusCode: 200 })

    // Get menu
    const menu = await getMenuByDateString(text)

    // Send menu to response URL
    await axios.post(response_url, {
        response_type: 'ephemeral',
        text: menu 
    })
}

module.exports.clearMenuCache = () => getMenuByDateMemoized.clear()

module.exports.setSlackToken = (token) => { SLACK_TOKEN = token }
