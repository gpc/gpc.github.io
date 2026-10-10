#!/usr/bin/env groovy
/*
 * Lists and drops stale staging repositories on the Sonatype OSSRH staging API.
 * See https://github.com/gpc/.github/issues/1
 *
 * Credentials are read from SONATYPE_USERNAME / SONATYPE_PASSWORD, falling back to
 * ~/.groovy/sonatype-secrets.yml:
 *   username: <portal token username>
 *   password: <portal token password>
 *
 * The proxy only returns repositories opened with the same token, so use the one
 * CI publishes with (see .github/workflows/sonatype-staging-cleanup.yml).
 */
import groovy.cli.commons.CliBuilder
import groovy.json.JsonSlurper
import groovy.yaml.YamlSlurper

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

final String BASE_URL = 'https://ossrh-staging-api.central.sonatype.com/manual'
final File SECRETS = new File(System.getProperty('user.home'), '.groovy/sonatype-secrets.yml')

def cli = new CliBuilder(usage: 'sonatype-staging-cleanup.groovy [list|drop]',
        header: 'List (default) or drop staging repositories on ossrh-staging-api.central.sonatype.com')
cli.with {
    p(longOpt: 'profile', args: 1, argName: 'id', 'Staging profile id, empty for all (default: io.github.gpc)')
    s(longOpt: 'state', args: 1, argName: 'state', 'Repository state: open, closed, released or all (default: closed)')
    i(longOpt: 'ip', args: 1, argName: 'ip', 'Repositories created from: any, client (this machine) or a specific IP (default: any)')
    f(longOpt: 'filter', args: 1, argName: 'regex', 'Only include repositories whose description matches this regex (find)')
    y(longOpt: 'yes', 'Do not ask for confirmation before dropping')
    n(longOpt: 'dry-run', 'Only print what would be dropped')
    h(longOpt: 'help', 'Show usage')
}
cli.stopAtNonOption = false
def options = cli.parse(args)
if (!options) System.exit(1)
if (options.h) {
    cli.usage()
    return
}
String command = options.arguments() ? options.arguments()[0] : 'list'
if (!(command in ['list', 'drop'])) {
    System.err.println "Unknown command '$command'"
    cli.usage()
    System.exit(1)
}

// --- credentials -----------------------------------------------------------
def fail = { String msg ->
    System.err.println msg
    System.exit(1)
}
String secretsHelp = """\
Set SONATYPE_USERNAME and SONATYPE_PASSWORD, or create ${SECRETS}
with your central.sonatype.com user token:

  username: <token username>
  password: <token password>
"""
def secrets = [username: System.getenv('SONATYPE_USERNAME'), password: System.getenv('SONATYPE_PASSWORD')]
if (!secrets.username || !secrets.password) {
    if (!SECRETS.file) fail("No credentials found.\n$secretsHelp")
    secrets = new YamlSlurper().parse(SECRETS)
    if (!(secrets instanceof Map) || !secrets.username || !secrets.password) {
        fail("Secrets file must contain 'username' and 'password'.\n$secretsHelp")
    }
}
String token = "${secrets.username}:${secrets.password}".bytes.encodeBase64().toString()

// --- http ------------------------------------------------------------------
HttpClient http = HttpClient.newHttpClient()

def send = { String method, String path, String accept ->
    def request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
            .header('Authorization', "Bearer $token")
            .header('Accept', accept)
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) {
        fail("$method $path failed: HTTP ${response.statusCode()}\n${response.body()}")
    }
    response.body()
}

def enc = { String s -> URLEncoder.encode(s, StandardCharsets.UTF_8) }

// --- search ----------------------------------------------------------------
String profile = options.p == false ? 'io.github.gpc' : options.p
String state = options.s ?: 'closed'
String ip = options.i ?: 'any'
String query = "ip=${enc(ip)}" + (profile ? "&profile_id=${enc(profile)}" : '') + (state == 'all' ? '' : "&state=${enc(state)}")
def json = new JsonSlurper().parseText(send('GET', "/search/repositories?$query", 'application/json'))
List repositories = json.repositories ?: []
if (options.f) {
    def pattern = ~options.f
    repositories = repositories.findAll { it.description =~ pattern }
}

if (!repositories) {
    println "No ${state == 'all' ? '' : state + ' '}repositories found${profile ? " for profile $profile" : ''}${options.f ? " matching '${options.f}'" : ''}."
    return
}

println "Found ${repositories.size()} ${state == 'all' ? '' : state + ' '}repositor${repositories.size() == 1 ? 'y' : 'ies'}${profile ? " for profile $profile" : ''}:"
repositories.each {
    println "  ${it.description}  [${it.state}]"
    println "    key:        ${it.key}"
    println "    deployment: ${it.portal_deployment_id}"
}

if (command == 'list') return

// --- drop ------------------------------------------------------------------
if (options.n) {
    println '\nDry run, would send:'
    repositories.each { println "  DELETE $BASE_URL/drop/repository/${enc(it.key)}" }
    return
}

if (!options.y) {
    print "\nDrop ${repositories.size()} repositor${repositories.size() == 1 ? 'y' : 'ies'}? [y/N] "
    System.out.flush()
    String answer = System.console()?.readLine() ?: new BufferedReader(new InputStreamReader(System.in)).readLine()
    if (answer?.trim()?.toLowerCase() !in ['y', 'yes']) {
        println 'Aborted.'
        return
    }
}

repositories.each {
    print "Dropping ${it.key} ... "
    send('DELETE', "/drop/repository/${enc(it.key)}", '*/*')
    println 'done'
}
