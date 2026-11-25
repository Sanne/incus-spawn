# GitHub accounts for agents

An agent in an isx instance acts on GitHub with the account whose token you give isx.
`isx init` recommends a separate account for the agent, so that it never acts with all of your access.
A new account owns no repositories, though, and cannot write to yours.

This page lists the ways to let it work on your repositories, with what each one needs and what the agent can then do.
It recommends none of them, since which one fits depends on how you work.

## What the token page gives

`isx init` and `isx account add github` give you the address of a GitHub page that creates a fine-grained token.
Its permissions are filled in: read and write on contents, issues and pull requests, and read on email addresses.
You choose the resource owner, the repository access and the expiry on the page.

GitHub limits a fine-grained token in ways that decide what the agent can do:

- It reaches only what one resource owner owns: the account itself, or one organization the account is a member of.
- It can read every public repository on GitHub, whatever you choose under "Repository access".
- It cannot write to a public repository that the account neither owns nor belongs to through an organization.
  So it cannot push there, open a pull request or an issue there, or comment there.
- It cannot write to a repository where the account is only an outside or repository collaborator.
  A classic token can, and so can the token `gh auth login --web` gets.

"All repositories" under "Repository access" also covers repositories the account gets later, such as a fork made later.
"Only select repositories" does not, so a fork made later needs the token edited.

GitHub lists these limits in [Managing your personal access tokens](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens).
It says they are gaps it is working to close, so check that page before you rely on one.

## The arrangements side by side

| Arrangement | What you set up | The token | What the agent can do |
| --- | --- | --- | --- |
| A fork | The agent's account forks your public repository. | The token page as filled in, with "All repositories". | Push branches to its fork and open pull requests in it, and issues once the fork's settings turn them on. It cannot open a pull request in your repository or comment there. |
| An organization | Your repositories belong to an organization, and the agent's account is a member. | The token page as filled in, with the organization as "Resource owner". | Push, open pull requests and issues, and comment in that organization's repositories, as far as the account's role there allows. Copilot CLI needs gh's token instead, since the token page cannot give an organization's token its permission. |
| A collaborator | You invite the agent's account as a collaborator on your own repositories. | A classic token with the `repo` scope, since a fine-grained token cannot write there. | Push, open pull requests and issues, and comment in each repository you invited it to. |

## A fork

The agent works in its own copy of your repository, and nothing it does changes yours.
A pull request from the fork into your repository needs a token that can write to your repository, which this one cannot.
You open that pull request yourself, from the fork's branch, when you want the agent's work.

The agent cannot create the fork itself with this token, since the token does not have the permission GitHub requires to create one.
Sign in as the agent's account and fork the repository in the browser.

A private repository can be forked only by an account that already has access to it, as a collaborator or an organization member.
The fork then belongs to the agent's account.
A token whose resource owner is the agent's account reaches the fork but not your private repository, so the agent cannot fetch your later changes from it.

If you remove the account's access to your private repository, GitHub deletes its fork, and any work you have not merged is lost.
The repository's admins, and the owners of an organization it belongs to, can also forbid forks of it.

## An organization

Your repositories move to an organization, or already live in one, and you add the agent's account as a member.
On the token page, choose the organization under "Resource owner", since the page starts with the account itself.
The organization can require an owner to approve each fine-grained token.
Until someone approves it, the token reads only public repositories.

The organization can also refuse fine-grained tokens altogether, and then it is not offered as a resource owner.
A token reaches one organization only, so work in two organizations needs a token for each, added to isx as two accounts.

The email addresses permission applies only when the account itself is the resource owner.
Without it, `isx init` reads the address on the account's public profile instead.
When there is one, it asks which address the agent's commits carry, and Enter takes GitHub's private address for the account.

Copilot CLI cannot use a token whose resource owner is the organization.
It needs the "Copilot Requests" permission, which is also an account permission.
It takes [the token gh gets](#copilot-cli-and-the-token-gh-gets) for the agent's account, unless the organization restricts gh's access.

## A collaborator

You invite the agent's account to each repository you want it to work on, and the account accepts.
GitHub does not let a fine-grained token write to a repository where its account is a collaborator.
So this arrangement needs a classic token with the `repo` scope, created while signed in as the agent's account.

A classic token reaches every repository its account can reach, with no choice of repositories or permissions.
For an account that only the agent uses, that is the repositories you invited it to and the ones it owns.
It can also open issues and pull requests and comment in any public repository, which a fine-grained token cannot.

GitHub Copilot CLI does not take a classic token.
It takes [the token gh gets](#copilot-cli-and-the-token-gh-gets) for the agent's account, which can also write where that account is a collaborator.

## Copilot CLI and the token gh gets

Copilot CLI takes a fine-grained token only when the account itself is its resource owner and you add "Copilot Requests" to it.
In the other arrangements it takes the token that gh gets when you sign it in through the browser.

The steps below need gh 2.40 or later, which holds several accounts at once.
An older gh replaces your own sign-in with the agent's account, and has no `gh auth switch`.

To get that token, run `gh auth login --web` on your computer.
It opens your own browser, which is signed in as you, so close that tab.
Enter the code gh prints at https://github.com/login/device, in the window signed in as the agent's account.

Run `gh auth switch`, so that gh on your computer acts as you again.
Then paste the output of `gh auth token --user <login>`, with the agent account's login, into `isx account add github`.

## Related pages

- [Configuration](CONFIGURATION.md#credential-accounts): several GitHub accounts in isx, and which instance uses which.
- [Agents](AGENTS.md): the agents isx installs, GitHub Copilot CLI included.
