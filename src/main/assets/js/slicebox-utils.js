angular.module('slicebox.utils', [])

.factory('openConfirmationDeleteModal', function($modal) {

    return function(title, message, deleteCallback) {

        var modalInstance = $modal.open({
                templateUrl: '/assets/partials/confirmDeleteModalContent.html',
                controller: 'SbxConfirmDeleteModalController',
                resolve: {
                        title: function() {
                            return title;
                        },
                        message: function() {
                            return message;
                        },
                        deleteCallback: function() {
                            return deleteCallback;
                        }
                    }
            });

        return modalInstance.result;
    };
})

.controller('SbxConfirmDeleteModalController', function ($scope, $q, $modalInstance, title, message, deleteCallback) {
    $scope.title = title;
    $scope.message = message;

    $scope.uiState = {
        deleteInProgress: false
    };

    $scope.closeErrorMessageAlert = function() {
        $scope.uiState.errorMessage = null;
    };

    $scope.deleteButtonClicked = function () {
        var deletePromise = deleteCallback();

        $scope.uiState.errorMessage = null;
        $scope.uiState.deleteInProgress = true;

        deletePromise.then(function() {
            $modalInstance.close();
        }, function(error) {
            $scope.uiState.errorMessage = error;
        });

        deletePromise.finally(function() {
            $scope.uiState.deleteInProgress = false;
        });
    };    

    $scope.cancelButtonClicked = function () {
        $modalInstance.close();
    };
});