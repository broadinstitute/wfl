# Development Process

This is a development process we are tying to standardize within the team, and encourage ourselves to follow in most cases.

## Summary

We always make feature branches from `master`, make pull requests, ask for reviews and merge back to `master` on Github. 

Currently we always deploy the latest master to the development environment after merge, but in the future, we might need to cut off releases on master and deployed the released versions to the server only. It's not decided yet.

## Steps

1. Clone the repo
    ```
    git@github.com:broadinstitute/wfl.git
    ```

2. Start from the latest copy of the remote master
    ```
    git checkout master
    git pull origin master
    ```

3. Create a feature branch
    
    _It is highly recommend that you follow the naming convention
    shown below so JIRA could pick up the branch and link it
    to our JIRA board._
    ```
    git checkout -b tbl/GH-666-feature-branch-something
    ```

4. Start your work, add and commit your changes
    ```
    git add "README.md"
    git commit -m "Update the readme file."
    ```

5. [Optional] Rebase onto lastet master: only if you want to get updates from the master
    ```
    git checkout master
    git pull origin master
    git checkout rex/feature-branch-something
    git rebase master
    ```

    alternatively, you could use the following commands without switching branches:
    ```
    git checkout tbl/GH-666-feature-branch-something
    git fetch origin master
    git merge master
    ```

6. Push branch to Github in the early stage of your development (recommended):
    ```
    git push --set-upstream origin rex/feature-branch-something
    ```

7. Create the pull request on Github UI. Be sure to fill out the PR description following the PR template instructions.

    - If the PR is still in development, make sure use the dropdown menu and choose `Create draft pull request`

    - If the PR is ready for review, click `Create pull request`.

8. Look for a reviewer in the team. 

9. Address reviewer comments with more commits.

10. Receive approval from reviewers. 

11. Make sure build the backend code at least once with:
    ```
    boot build
    ```

12. Merge the PR. The feature branch will be automatically cleaned up.

13. [Temporary] Fetch the lastest master branch again and deploy it to dev server.
    ```
    git checkout master
    git pull origin master
    boot deploy
    ```
    
    you might need to login to vault and google by the following commands before you want to deploy:
    ```
    vault auth -method=github token=$(cat ~/.github-token)
    gcloud auth login
    ```

    **Note: this action might interfere other people's work that is under QA, please always coordinate before you do this!** 
