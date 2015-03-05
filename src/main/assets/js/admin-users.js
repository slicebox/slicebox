(function () {
   'use strict';
}());

angular.module('slicebox.adminUsers', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/admin/users', {
    templateUrl: '/assets/partials/adminUsers.html',
    controller: 'AdminUsersCtrl'
  });
})

.controller('AdminUsersCtrl', function($scope, $http, $modal, $q, openConfirmationDeleteModal) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: confirmDeleteUsers
            }
        ];

    $scope.callbacks = {};

    // Scope functions
    $scope.loadUsersPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/users');
    };

    $scope.addUserButtonClicked = function() {
        var modalInstance = $modal.open({
                templateUrl: '/assets/partials/addUserModalContent.html',
                controller: 'AddUserModalCtrl'
            });

        modalInstance.result.then(function(user) {
            $scope.uiState.addUserInProgress = true;

            var addUserPromise = $http.post('/api/users', user);

            addUserPromise.error(function(data) {
                $scope.appendErrorMessage(data);
            });

            addUserPromise.finally(function() {
                $scope.uiState.addUserInProgress = false;
                $scope.callbacks.usersTable.reloadPage();
            });
        });
    };

    // Private functions
    function confirmDeleteUsers(users) {
        var deleteConfirmationText = 'Permanently delete ' + users.length + ' users?';

        return openConfirmationDeleteModal('Delete Users', deleteConfirmationText, function() {
            return deleteUsers(users);
        });
    }

    function deleteUsers(users) {
        var deletePromises = [];
        var deletePromise;

        angular.forEach(users, function(user) {
            deletePromise = $http.delete('/api/users/' + user.id);
            deletePromises.push(deletePromise);
        });

        return $q.all(deletePromises);
    }
})

.controller('AddUserModalCtrl', function($scope, $modalInstance) {
    // Initialization
    $scope.role = 'USER';

    // Scope functions
    $scope.addButtonClicked = function() {
        $modalInstance.close({ user: $scope.userName, password: $scope.password, role: $scope.role});
    };

    $scope.cancelButtonClicked = function() {
        $modalInstance.dismiss();
    };
});