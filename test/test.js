const handler = require('../handler')
const chai = require('chai')
const nock = require('nock')
const sinon = require('sinon')

const should = chai.should()

beforeEach(() => {
    handler.clearMenuCache()
    handler.setSlackToken("test_token")

    nock.disableNetConnect()
    nock.cleanAll()

    // Given that menus from 11/02/19 to 16/02/19 (week 7 of 2019) are available on the standard URL
    nock('http://www.ox-linz.at')
        .persist()
        .get('/fileadmin/Mittagsmenue/2019/OX_Linz_A5_Wochenmenue__KW7.pdf')
        .replyWithFile(200, __dirname + '/menu_2019_7.pdf')

    nock('http://www.ox-linz.at')
        .persist()
        .get('/fileadmin/Mittagsmenue/OX_Linz_A5_Wochenmenue__KW7.pdf')
        .reply(404)

    // AND that menus from 18/02/19 to 23/02 (week 8 of 2019) are available on the alternative URL
    nock('http://www.ox-linz.at')
        .persist()
        .get('/fileadmin/Mittagsmenue/2019/OX_Linz_A5_Wochenmenue__KW8.pdf')
        .reply(404)

    nock('http://www.ox-linz.at')
        .persist()
        .get('/fileadmin/Mittagsmenue/OX_Linz_A5_Wochenmenue__KW8.pdf')
        .replyWithFile(200, __dirname + '/menu_2019_8.pdf')
})

describe('getMenu', () => {

    it('should work normaly', async () => {
        const menu = await handler.getMenu('15/02')
        menu.should.be.equal(
            '*Freitag // 15. Februar*\n' +
            'Knoblauchcremesuppe\n' +
            'Gegrillte Fischfilet mit Petersilienkartoffeln\n' +
            'Chicken Burger mit Pommes frites'
        )
    })

    it('should work with alternative URL', async () => {
        const menu = await handler.getMenu('23/02')
        menu.should.be.equal(
            '*Samstag // 23. Februar*\n' +
            'Orangen-Karottensuppe\n' +
            'Rinderfiletspitzen in Pfefferrahmsauce und Butternudeln'
        )
    })
    
    it('should fail when menu is unavailable', async () => {
        const menu = await handler.getMenu('24/02')
        menu.should.be.equal("There's no menu available at the given date.")
    })

    it('should fail when date is unparseable', async () => {
        const menu = await handler.getMenu('not a date')
        menu.should.be.equal("The input date couldn't be parsed.")
    })
})

describe('endpoint', () => {

    it('should work normaly', async () => {
        // Given that response URL is working as expected
        const responseScope = nock('http://test.callback')
            .post('/', {
                response_type: "ephemeral",
                text : '*Freitag // 15. Februar*\n' +
                'Knoblauchcremesuppe\n' +
                'Gegrillte Fischfilet mit Petersilienkartoffeln\n' +
                'Chicken Burger mit Pommes frites'
            })
            .reply(200)

        // When endpoint is hit with a given date
        const event = {
            body: 'token=test_token&' +
                'text=15/02&' +
                'response_url=http://test.callback'
        }
        const context = sinon.mock()
        const callback = sinon.spy()
        await handler.endpoint(event, context, callback)

        // Then the returned status code is 200
        sinon.assert.calledWith(callback, null, { statusCode: 200 })

        // And callback URL is accessed
        responseScope.done()
    })

    it('should refuse invalid tokens', async () => {
        // When endpoint is hit with an invalid token
        const event = {
            body: 'token=invalid_token&' +
                'text=15/02&' +
                'response_url=http://test.callback'
        }
        const context = sinon.mock()
        const callback = sinon.spy()
        await handler.endpoint(event, context, callback)

        // Then the returned status code is 403
        sinon.assert.calledWith(callback, null, { statusCode: 403 })
    })
})