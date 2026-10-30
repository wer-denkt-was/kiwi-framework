// GraalJS provides neither `atob` nor Node's `Buffer`, but "entities" (used by cheerio)
// requires one of them to decode its embedded entity tables at load time
if (typeof globalThis.atob !== 'function') {
    const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'

    globalThis.atob = (data) => {
        const input = String(data).replace(/[\t\n\f\r =]/g, '')
        let output = ''
        let buffer = 0
        let bits = 0
        for (const char of input) {
            const value = alphabet.indexOf(char)
            if (value < 0) {
                throw new Error(`Invalid base64 character: ${char}`)
            }
            buffer = (buffer << 6) | value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output += String.fromCharCode((buffer >> bits) & 0xff)
                buffer &= (1 << bits) - 1
            }
        }
        return output
    }
}
